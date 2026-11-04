package io.agentscope.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 接缝 3：用真实 HTTP 客户端打完整的 OIDC 流程，不 mock 协议层。
 *
 * <p>数据库用 Testcontainers MySQL 8.4 —— SAS 的 JDBC schema 是 MySQL 方言，
 * 用 H2 会掩盖列类型/时间区问题。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthOidcIntegrationTest {

    private static final String CLIENT_ID = "workbench";
    private static final String CLIENT_SECRET = "workbench-secret";
    private static final String REDIRECT_URI = "http://localhost:8080/login/oauth2/code/workbench";
    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~abcd";
    private static final String ADMIN = "admin";
    private static final String PASSWORD = "admin123";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agent_platform")
            .withUsername("app")
            .withPassword("apppass");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + "?preserveInstants=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @LocalServerPort int port;

    @Test
    void fullAuthorizationCodePkceFlow() throws Exception {
        Browser browser = new Browser(port);
        String challenge = s256(VERIFIER);

        // 1) 未登录访问授权端点 → 跳登录页
        HttpResponse<String> authorize = browser.get(authorizeUrl(challenge, "state-1"));
        assertEquals(302, authorize.statusCode(), "未登录应 302");
        assertTrue(authorize.headers().firstValue("location").orElse("").contains("/login"));

        // 2) 登录（带 CSRF）
        assertTrue(browser.login(), "管理员应能登录");

        // 3) 再走授权端点 → 拿到 code
        HttpResponse<String> authorized = browser.get(authorizeUrl(challenge, "state-1"));
        assertEquals(302, authorized.statusCode());
        String location = authorized.headers().firstValue("location").orElse("");
        assertTrue(location.startsWith(REDIRECT_URI), "实际跳转: " + location);
        String code = queryParam(location, "code");
        assertNotNull(code, "应返回授权码");
        assertEquals("state-1", queryParam(location, "state"));

        // 4) 换 token
        Map<String, Object> token = browser.exchangeCode(code, VERIFIER);
        assertNotNull(token.get("access_token"));
        assertNotNull(token.get("id_token"));
        assertNotNull(token.get("refresh_token"));

        // 5) userinfo 带 RBAC claims
        Map<String, Object> userInfo = browser.userInfo((String) token.get("access_token"));
        assertEquals("default", userInfo.get("tenant"));
        assertTrue(((java.util.List<?>) userInfo.get("role")).contains("ADMIN"));
        assertTrue(((java.util.List<?>) userInfo.get("perms")).contains("client:rotate-secret"));

        // 6) JWKS 可被外部独立验签
        assertTrue(jwksVerifies((String) token.get("id_token")), "id_token 应能用 /oauth2/jwks 的公钥验签");
    }

    @Test
    void pkceMismatchIsRejected() throws Exception {
        Browser browser = new Browser(port);
        assertTrue(browser.login());
        HttpResponse<String> authorized = browser.get(authorizeUrl(s256(VERIFIER), "s"));
        String code = queryParam(authorized.headers().firstValue("location").orElse(""), "code");
        assertNotNull(code);

        Map<String, Object> token = browser.exchangeCodeRaw(code, "a-completely-wrong-verifier-value-1234567890");
        assertEquals("invalid_grant", token.get("error"), "PKCE 校验失败应返回 invalid_grant");
    }

    @Test
    void authorizationCodeIsSingleUse() throws Exception {
        Browser browser = new Browser(port);
        assertTrue(browser.login());
        HttpResponse<String> authorized = browser.get(authorizeUrl(s256(VERIFIER), "s2"));
        String code = queryParam(authorized.headers().firstValue("location").orElse(""), "code");
        assertNotNull(code);

        assertTrue(browser.exchangeCodeRaw(code, VERIFIER).containsKey("access_token"));
        Map<String, Object> replay = browser.exchangeCodeRaw(code, VERIFIER);
        assertEquals("invalid_grant", replay.get("error"), "授权码重放应被拒");
    }

    @Test
    void refreshTokenRotatesAndOldOneIsRejected() throws Exception {
        Browser browser = new Browser(port);
        assertTrue(browser.login());
        HttpResponse<String> authorized = browser.get(authorizeUrl(s256(VERIFIER), "refresh"));
        String code = queryParam(authorized.headers().firstValue("location").orElse(""), "code");
        Map<String, Object> first = browser.exchangeCodeRaw(code, VERIFIER);
        String oldRefresh = (String) first.get("refresh_token");
        assertNotNull(oldRefresh);

        Map<String, Object> rotated = browser.refresh(oldRefresh);
        assertNotNull(rotated.get("access_token"), "刷新应返回新的 access token");
        String newRefresh = (String) rotated.get("refresh_token");
        assertNotNull(newRefresh);
        assertTrue(!oldRefresh.equals(newRefresh), "reuseRefreshTokens(false) 下应换发新 refresh token");

        Map<String, Object> reuse = browser.refresh(oldRefresh);
        assertTrue(reuse.containsKey("error"), "旧 refresh token 必须失效");
    }

    @Test
    void unregisteredClientIsRejectedAndDynamicRegistrationIsNotExposed() throws Exception {
        Browser browser = new Browser(port);
        String badBasic = Base64.getEncoder().encodeToString(
                ("unknown-client:whatever").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> tokenResponse = browser.post(
                "/oauth2/token", "application/x-www-form-urlencoded",
                "grant_type=authorization_code&code=dummy",
                "Authorization", "Basic " + badBasic);
        assertEquals(401, tokenResponse.statusCode(), "未登记客户端应 401");

        assertTrue(browser.login(), "先登录才能确认端点确实不存在（否则会被登录重定向掩盖）");
        assertEquals(404, browser.get("/connect/register").statusCode(),
                "动态客户端注册端点不应存在");
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        Browser browser = new Browser(port);
        String expired = SignedTokens.expired(port);
        HttpResponse<String> response = browser.get("/userinfo", "Authorization", "Bearer " + expired);
        assertEquals(401, response.statusCode(), "过期 access token 应被拒");
    }

    @Test
    void jwksIsPublicAndHealthIsAnonymous() throws Exception {
        Browser browser = new Browser(port);
        assertEquals(200, browser.get("/.well-known/openid-configuration").statusCode());
        assertEquals(200, browser.get("/oauth2/jwks").statusCode());
        assertEquals(200, browser.get("/api/health").statusCode());
    }

    // ------------------------------------------------------------------

    private String authorizeUrl(String challenge, String state) {
        return "/oauth2/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&scope=openid%20profile"
                + "&state=" + state
                + "&nonce=n-1"
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";
    }

    private static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String queryParam(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]+)").matcher(url);
        return m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    private boolean jwksVerifies(String idToken) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String jwks = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/jwks")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
        RSAKey key = JWKSet.parse(jwks).getKeys().stream()
                .filter(k -> k instanceof RSAKey)
                .map(k -> (RSAKey) k)
                .findFirst()
                .orElseThrow();
        JWSVerifier verifier = new RSASSAVerifier(key.toRSAPublicKey());
        return SignedJWT.parse(idToken).verify(verifier);
    }

    /** 一个带 cookie  jar、不自动跟随重定向的浏览器替身。 */
    private static final class Browser {
        private final String base;
        private final HttpClient http;

        Browser(int port) {
            this.base = "http://localhost:" + port;
            CookieManager cookies = new CookieManager();
            cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            this.http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .cookieHandler(cookies)
                    .build();
        }

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return http.send(
                    HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String contentType, String body, String... headers)
                throws IOException, InterruptedException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            for (int i = 0; i < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }

        boolean login() throws IOException, InterruptedException {
            String html = get("/login").body();
            Matcher csrf = Pattern.compile("name=\"(_csrf)\" value=\"([^\"]+)\"").matcher(html);
            if (!csrf.find()) {
                return false;
            }
            String form = "username=" + enc(ADMIN) + "&password=" + enc(PASSWORD)
                    + "&" + csrf.group(1) + "=" + enc(csrf.group(2));
            HttpResponse<String> response =
                    post("/auth/login", "application/x-www-form-urlencoded", form);
            return response.statusCode() == 302;
        }

        Map<String, Object> exchangeCode(String code, String verifier) throws Exception {
            return exchangeCodeRaw(code, verifier);
        }

        Map<String, Object> exchangeCodeRaw(String code, String verifier) throws Exception {
            String basic = Base64.getEncoder().encodeToString(
                    (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            String form = "grant_type=authorization_code&code=" + enc(code)
                    + "&redirect_uri=" + enc(REDIRECT_URI)
                    + "&code_verifier=" + enc(verifier);
            HttpResponse<String> response = post(
                    "/oauth2/token", "application/x-www-form-urlencoded", form,
                    "Authorization", "Basic " + basic);
            return Json.parseObject(response.body());
        }

        Map<String, Object> refresh(String refreshToken) throws Exception {
            String basic = Base64.getEncoder().encodeToString(
                    (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            String form = "grant_type=refresh_token&refresh_token=" + enc(refreshToken);
            HttpResponse<String> response = post(
                    "/oauth2/token", "application/x-www-form-urlencoded", form,
                    "Authorization", "Basic " + basic);
            return Json.parseObject(response.body());
        }

        Map<String, Object> userInfo(String accessToken) throws Exception {
            HttpResponse<String> response = get("/userinfo",
                    "Authorization", "Bearer " + accessToken);
            return Json.parseObject(response.body());
        }

        HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
            for (int i = 0; i < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 用仓库内的 dev 私钥构造一个已过期但签名有效的 access token。 */
    private static final class SignedTokens {
        static String expired(int port) throws Exception {
            String pem = new String(
                    io.agentscope.platform.auth.AuthOidcIntegrationTest.class
                            .getResourceAsStream("/keys/dev-rsa.pem").readAllBytes(),
                    StandardCharsets.UTF_8);
            String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            java.security.KeyFactory factory = java.security.KeyFactory.getInstance("RSA");
            java.security.interfaces.RSAPrivateCrtKey key =
                    (java.security.interfaces.RSAPrivateCrtKey) factory.generatePrivate(
                            new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));

            var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .subject("1")
                    .issuer("http://localhost:" + port)
                    .issueTime(new java.util.Date(System.currentTimeMillis() - 7200_000))
                    .expirationTime(new java.util.Date(System.currentTimeMillis() - 3600_000))
                    .jwtID(java.util.UUID.randomUUID().toString())
                    .claim("scope", java.util.List.of("openid", "profile"))
                    .build();
            var jwt = new com.nimbusds.jwt.SignedJWT(
                    new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                            .keyID("auth-signing-key-1").type(com.nimbusds.jose.JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
            return jwt.serialize();
        }
    }

    /** 极简 JSON 对象解析，避免为测试再引一个库。 */
    private static final class Json {
        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String json) throws Exception {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        }
    }
}
