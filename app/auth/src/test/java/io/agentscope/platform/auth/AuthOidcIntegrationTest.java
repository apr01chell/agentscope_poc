package io.agentscope.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.agentscope.platform.auth.support.TestBrowser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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
 * 用 H2 会掩盖列类型/时区问题。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthOidcIntegrationTest {

    static final String CLIENT_ID = "workbench";
    static final String CLIENT_SECRET = "workbench-secret";
    static final String REDIRECT_URI = "http://localhost:8080/login/oauth2/code/workbench";
    static final String VERIFIER =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~abcd";

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
        TestBrowser browser = new TestBrowser(port);
        String challenge = TestBrowser.s256(VERIFIER);

        HttpResponse<String> authorize = browser.get(authorizeUrl(challenge, "state-1"));
        assertEquals(302, authorize.statusCode(), "未登录应 302");
        assertTrue(authorize.headers().firstValue("location").orElse("").contains("/login"));

        assertTrue(browser.loginAsAdmin(), "管理员应能登录");

        HttpResponse<String> authorized = browser.get(authorizeUrl(challenge, "state-1"));
        assertEquals(302, authorized.statusCode());
        String location = authorized.headers().firstValue("location").orElse("");
        assertTrue(location.startsWith(REDIRECT_URI), "实际跳转: " + location);
        String code = TestBrowser.queryParam(location, "code");
        assertNotNull(code, "应返回授权码");
        assertEquals("state-1", TestBrowser.queryParam(location, "state"));

        Map<String, Object> token = exchange(browser, code, VERIFIER);
        assertNotNull(token.get("access_token"));
        assertNotNull(token.get("id_token"));
        assertNotNull(token.get("refresh_token"));

        Map<String, Object> userInfo = browser.userInfo((String) token.get("access_token"));
        assertEquals("default", userInfo.get("tenant"));
        assertTrue(((List<?>) userInfo.get("role")).contains("ADMIN"));
        assertTrue(((List<?>) userInfo.get("perms")).contains("client:rotate-secret"));

        assertTrue(jwksVerifies((String) token.get("id_token")), "id_token 应能用 /oauth2/jwks 的公钥验签");
    }

    @Test
    void pkceMismatchIsRejected() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        assertTrue(browser.loginAsAdmin());
        String code = codeFrom(browser, TestBrowser.s256(VERIFIER), "s");
        Map<String, Object> token = exchange(browser, code, "a-completely-wrong-verifier-value-1234567890");
        assertEquals("invalid_grant", token.get("error"), "PKCE 校验失败应返回 invalid_grant");
    }

    @Test
    void authorizationCodeIsSingleUse() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        assertTrue(browser.loginAsAdmin());
        String code = codeFrom(browser, TestBrowser.s256(VERIFIER), "s2");

        assertTrue(exchange(browser, code, VERIFIER).containsKey("access_token"));
        assertEquals("invalid_grant", exchange(browser, code, VERIFIER).get("error"), "授权码重放应被拒");
    }

    @Test
    void refreshTokenRotatesAndOldOneIsRejected() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        assertTrue(browser.loginAsAdmin());
        String code = codeFrom(browser, TestBrowser.s256(VERIFIER), "refresh");
        Map<String, Object> first = exchange(browser, code, VERIFIER);
        String oldRefresh = (String) first.get("refresh_token");
        assertNotNull(oldRefresh);

        Map<String, Object> rotated = browser.refresh(oldRefresh, CLIENT_ID, CLIENT_SECRET);
        assertNotNull(rotated.get("access_token"), "刷新应返回新的 access token");
        String newRefresh = (String) rotated.get("refresh_token");
        assertNotNull(newRefresh);
        assertNotEquals(oldRefresh, newRefresh, "reuseRefreshTokens(false) 下应换发新 refresh token");

        assertTrue(browser.refresh(oldRefresh, CLIENT_ID, CLIENT_SECRET).containsKey("error"),
                "旧 refresh token 必须失效");
    }

    @Test
    void unregisteredClientIsRejectedAndDynamicRegistrationIsNotExposed() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        String badBasic = java.util.Base64.getEncoder()
                .encodeToString("unknown-client:whatever".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpResponse<String> tokenResponse = browser.post(
                "/oauth2/token", "application/x-www-form-urlencoded",
                "grant_type=authorization_code&code=dummy",
                "Authorization", "Basic " + badBasic);
        assertEquals(401, tokenResponse.statusCode(), "未登记客户端应 401");

        assertTrue(browser.loginAsAdmin(), "先登录，否则 /connect/register 会被登录重定向掩盖");
        assertEquals(404, browser.get("/connect/register").statusCode(), "动态注册端点不应存在");
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        String expired = ExpiredTokens.build(port);
        assertEquals(401, browser.get("/userinfo", "Authorization", "Bearer " + expired).statusCode(),
                "过期 access token 应被拒");
    }

    @Test
    void jwksIsPublicAndHealthIsAnonymous() throws Exception {
        TestBrowser browser = new TestBrowser(port);
        assertEquals(200, browser.get("/.well-known/openid-configuration").statusCode());
        assertEquals(200, browser.get("/oauth2/jwks").statusCode());
        assertEquals(200, browser.get("/api/health").statusCode());
    }

    // ------------------------------------------------------------------

    private String authorizeUrl(String challenge, String state) {
        return "/oauth2/authorize?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&redirect_uri=" + TestBrowser.enc(REDIRECT_URI)
                + "&scope=openid%20profile"
                + "&state=" + state
                + "&nonce=n-1"
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";
    }

    private String codeFrom(TestBrowser browser, String challenge, String state) throws Exception {
        HttpResponse<String> authorized = browser.get(authorizeUrl(challenge, state));
        return TestBrowser.queryParam(authorized.headers().firstValue("location").orElse(""), "code");
    }

    private Map<String, Object> exchange(TestBrowser browser, String code, String verifier) throws Exception {
        return browser.exchangeCode(code, verifier, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
    }

    private boolean jwksVerifies(String idToken) throws Exception {
        String jwks = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/jwks")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        RSAKey key = JWKSet.parse(jwks).getKeys().stream()
                .filter(k -> k instanceof RSAKey)
                .map(k -> (RSAKey) k)
                .findFirst()
                .orElseThrow();
        JWSVerifier verifier = new RSASSAVerifier(key.toRSAPublicKey());
        return SignedJWT.parse(idToken).verify(verifier);
    }
}
