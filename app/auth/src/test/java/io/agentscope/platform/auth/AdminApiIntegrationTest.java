package io.agentscope.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.platform.auth.support.TestBrowser;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 管理 API 的行为：权限校验、两条硬规则、secret 不可读回、审计留痕。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiIntegrationTest {

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

    @Autowired JdbcTemplate jdbc;

    private TestBrowser admin;

    @BeforeEach
    void signIn() throws Exception {
        admin = new TestBrowser(port);
        assertTrue(admin.loginAsAdmin(), "管理员应能登录");
        admin.loadCsrf();
    }

    @Test
    void adminCanListUsersRolesPermissionsAndClients() throws Exception {
        assertEquals(200, admin.adminGet("/users").statusCode());
        assertEquals(200, admin.adminGet("/roles").statusCode());
        assertEquals(200, admin.adminGet("/permissions").statusCode());
        assertEquals(200, admin.adminGet("/clients").statusCode());

        Map<String, Object> users = TestBrowser.parse(admin.adminGet("/users").body());
        assertTrue(String.valueOf(users.get("data")).contains("admin"));

        Map<String, Object> permissions = TestBrowser.parse(admin.adminGet("/permissions").body());
        assertEquals(14, ((List<?>) permissions.get("data")).size(), "v1 权限点应为 14 个");
    }

    @Test
    void meReturnsBusinessPermissionsOnly() throws Exception {
        Map<String, Object> me = TestBrowser.parse(admin.get("/auth/me").body());
        Map<?, ?> data = (Map<?, ?>) me.get("data");
        List<?> permissions = (List<?>) data.get("permissions");

        assertEquals(14, permissions.size(), "应只返回 14 个业务权限点");
        assertTrue(permissions.contains("client:rotate-secret"));
        assertFalse(permissions.contains("FACTOR_PASSWORD"),
                "不能把 Spring Security 的内部 authority 当成业务权限返回");
    }

    @Test
    void creatingUserWithRolesMakesItVisible() throws Exception {
        Map<String, Object> created = send("POST", "/users", Map.of(
                "username", "alice", "password", "alice123",
                "displayName", "Alice", "roleIds", List.of(2)));
        assertEquals(200, statusOf(created), "创建用户应成功: " + created);
        assertTrue(String.valueOf(created.get("data")).contains("alice"));
        assertTrue(String.valueOf(created.get("data")).contains("USER"));
    }

    @Test
    void nonAdminCannotCallAdminApi() throws Exception {
        send("POST", "/users", Map.of(
                "username", "bob", "password", "bob12345",
                "displayName", "Bob", "roleIds", List.of(2)));

        TestBrowser bob = new TestBrowser(port);
        assertTrue(bob.login("bob", "bob12345"), "普通用户应能登录");
        bob.loadCsrf();

        HttpResponse<String> response = bob.adminGet("/users");
        assertEquals(403, response.statusCode(), "普通用户调管理 API 应 403");
    }

    @Test
    void lastAdminCannotBeDeletedOrDemoted() throws Exception {
        Map<String, Object> users = TestBrowser.parse(admin.adminGet("/users").body());
        long adminId = firstUserId(users);

        assertEquals(409, admin.adminSend("DELETE", "/users/" + adminId, null).statusCode(),
                "不能禁用最后一个管理员");
        assertEquals(409, admin.adminSend("PUT", "/users/" + adminId + "/roles",
                        TestBrowser.toJson(Map.of("roleIds", List.of(2)))).statusCode(),
                "不能移除最后一个管理员的 ADMIN 角色");
    }

    @Test
    void builtInRoleCannotBeDeleted() throws Exception {
        Map<String, Object> roles = TestBrowser.parse(admin.adminGet("/roles").body());
        long adminRoleId = ((Number) ((Map<?, ?>) ((List<?>) roles.get("data")).get(0)).get("id")).longValue();

        assertEquals(409, admin.adminSend("DELETE", "/roles/" + adminRoleId, null).statusCode(),
                "内置角色不可删除");
    }

    @Test
    void rolePermissionChangeIsApplied() throws Exception {
        Map<String, Object> createdRole = send("POST", "/roles", Map.of(
                "code", "AUDITOR", "name", "审计员", "description", "只读审计", "permissionIds", List.of()));
        assertEquals(200, statusOf(createdRole), "建角色应成功: " + createdRole);
        long roleId = ((Number) ((Map<?, ?>) createdRole.get("data")).get("id")).longValue();

        Map<String, Object> permissions = TestBrowser.parse(admin.adminGet("/permissions").body());
        long auditPermissionId = permissionId(permissions, "audit:read");

        Map<String, Object> updated = TestBrowser.parse(admin.adminSend("PUT",
                "/roles/" + roleId + "/permissions",
                TestBrowser.toJson(Map.of("permissionIds", List.of(auditPermissionId)))).body());
        assertTrue(String.valueOf(updated.get("data")).contains("audit:read"),
                "角色应带上刚分配的权限: " + updated);
    }

    @Test
    void clientSecretIsNeverReadableAndRotationInvalidatesOldOne() throws Exception {
        Map<String, Object> created = send("POST", "/clients", Map.of(
                "clientId", "partner", "clientSecret", "partner-secret-1",
                "clientName", "合作方", "redirectUris", List.of("http://localhost:9999/cb"),
                "scopes", List.of("openid")));
        assertEquals(200, statusOf(created), "建客户端应成功: " + created);
        assertEquals("partner-secret-1", ((Map<?, ?>) created.get("data")).get("clientSecret"),
                "创建时明文返回一次");

        String listBody = admin.adminGet("/clients").body();
        assertFalse(listBody.contains("partner-secret-1"), "列表绝不能回显 secret");

        Map<String, Object> clients = TestBrowser.parse(listBody);
        String clientRowId = clientIdOf(clients, "partner");
        assertNotNull(clientRowId);

        Map<String, Object> rotated = TestBrowser.parse(admin.adminSend(
                "POST", "/clients/" + clientRowId + "/rotate-secret", null).body());
        String newSecret = (String) ((Map<?, ?>) rotated.get("data")).get("clientSecret");
        assertNotNull(newSecret);
        assertFalse("partner-secret-1".equals(newSecret), "应换发新 secret");

        // 用假 code 换 token 时：客户端认证失败 → 401 invalid_client；
        // 客户端认证通过但 code 无效 → 400 invalid_grant。用状态码区分这两件事。
        assertEquals(401, tokenStatus("partner", "partner-secret-1"), "旧 secret 应立即失效");
        assertEquals(400, tokenStatus("partner", newSecret), "新 secret 应通过客户端认证");
    }

    @Test
    void writesAreAudited() throws Exception {
        send("POST", "/users", Map.of(
                "username", "carol", "password", "carol123",
                "displayName", "Carol", "roleIds", List.of(2)));

        // 审计的查询界面归 #23，这里只验证"写了"，所以直接查库
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'user:create' AND target_id IS NOT NULL",
                Integer.class);
        assertNotNull(rows);
        assertTrue(rows > 0, "应写入 user:create 审计事件");
    }

    // ------------------------------------------------------------------

    private Map<String, Object> send(String method, String path, Object body) throws Exception {
        String json = body == null ? null : TestBrowser.toJson(body);
        return TestBrowser.parse(admin.adminSend(method, path, json).body());
    }

    private static int statusOf(Map<String, Object> body) {
        Object success = body.get("success");
        return Boolean.TRUE.equals(success) ? 200 : 400;
    }

    private int tokenStatus(String clientId, String secret) throws Exception {
        String basic = java.util.Base64.getEncoder()
                .encodeToString((clientId + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new TestBrowser(port).post(
                "/oauth2/token", "application/x-www-form-urlencoded",
                "grant_type=authorization_code&code=x",
                "Authorization", "Basic " + basic).statusCode();
    }

    private static long firstUserId(Map<String, Object> users) {
        Map<?, ?> first = (Map<?, ?>) ((List<?>) users.get("data")).get(0);
        return ((Number) first.get("id")).longValue();
    }

    private static long permissionId(Map<String, Object> permissions, String code) {
        return ((List<?>) permissions.get("data")).stream()
                .map(o -> (Map<?, ?>) o)
                .filter(m -> code.equals(m.get("code")))
                .map(m -> ((Number) m.get("id")).longValue())
                .findFirst()
                .orElseThrow();
    }

    private static String clientIdOf(Map<String, Object> clients, String clientId) {
        return ((List<?>) clients.get("data")).stream()
                .map(o -> (Map<?, ?>) o)
                .filter(m -> clientId.equals(m.get("clientId")))
                .map(m -> String.valueOf(m.get("id")))
                .findFirst()
                .orElse(null);
    }
}
