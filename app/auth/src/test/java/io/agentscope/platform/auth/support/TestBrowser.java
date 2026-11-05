package io.agentscope.platform.auth.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * 测试用的"浏览器替身"：带 cookie、不自动跟随重定向，能走完表单登录与 OIDC 流程。
 *
 * <p>抽出来是因为两个集成测试都要它（OIDC 与 管理 API）。复制两份迟早会漂移。
 */
public class TestBrowser {

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "admin123";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final HttpClient http;
    private String csrfToken;

    public TestBrowser(int port) {
        this.base = "http://localhost:" + port;
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(cookies)
                .build();
    }

    public String base() {
        return base;
    }

    // ---------- 基础请求 ----------

    public HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        applyHeaders(b, headers);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> post(String path, String contentType, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        applyHeaders(b, headers);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> send(String method, String path, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        if (csrfToken != null) {
            b.header("X-CSRF-TOKEN", csrfToken);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------- 登录 ----------

    public boolean login(String username, String password) throws IOException, InterruptedException {
        String html = get("/login").body();
        Matcher m = Pattern.compile("name=\"(_csrf)\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            return false;
        }
        String form = "username=" + enc(username) + "&password=" + enc(password)
                + "&" + m.group(1) + "=" + enc(m.group(2));
        return post("/auth/login", "application/x-www-form-urlencoded", form).statusCode() == 302;
    }

    public boolean loginAsAdmin() throws IOException, InterruptedException {
        return login(ADMIN, ADMIN_PASSWORD);
    }

    /** 取 CSRF 令牌，之后所有写请求自动带上。 */
    @SuppressWarnings("unchecked")
    public void loadCsrf() throws IOException, InterruptedException {
        Map<String, Object> body = parse(get("/auth/csrf").body());
        Object data = body.get("data");
        if (data instanceof Map<?, ?> map) {
            this.csrfToken = String.valueOf(map.get("token"));
        }
    }

    // ---------- 管理 API ----------

    public HttpResponse<String> adminGet(String path) throws IOException, InterruptedException {
        return send("GET", "/auth/admin" + path, null);
    }

    public HttpResponse<String> adminSend(String method, String path, String json)
            throws IOException, InterruptedException {
        return send(method, "/auth/admin" + path, json);
    }

    // ---------- OIDC ----------

    public Map<String, Object> exchangeCode(String code, String verifier, String clientId, String clientSecret,
            String redirectUri) throws IOException, InterruptedException {
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&code_verifier=" + enc(verifier);
        return parse(post("/oauth2/token", "application/x-www-form-urlencoded", form,
                "Authorization", "Basic " + basic).body());
    }

    public Map<String, Object> refresh(String refreshToken, String clientId, String clientSecret)
            throws IOException, InterruptedException {
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=refresh_token&refresh_token=" + enc(refreshToken);
        return parse(post("/oauth2/token", "application/x-www-form-urlencoded", form,
                "Authorization", "Basic " + basic).body());
    }

    public Map<String, Object> userInfo(String accessToken) throws IOException, InterruptedException {
        return parse(get("/userinfo", "Authorization", "Bearer " + accessToken).body());
    }

    // ---------- 工具 ----------

    public static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String queryParam(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]+)").matcher(url);
        return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return JSON.readValue(json, Map.class);
    }

    public static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }

    private URI uri(String path) {
        return URI.create(path.startsWith("http") ? path : base + path);
    }

    private static void applyHeaders(HttpRequest.Builder builder, String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
    }
}
