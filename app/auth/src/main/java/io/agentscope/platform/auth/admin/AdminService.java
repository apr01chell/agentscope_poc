package io.agentscope.platform.auth.admin;

import io.agentscope.platform.auth.admin.AdminDtos.ClientSummary;
import io.agentscope.platform.auth.admin.AdminDtos.CreateClient;
import io.agentscope.platform.auth.admin.AdminDtos.CreateRole;
import io.agentscope.platform.auth.admin.AdminDtos.CreateUser;
import io.agentscope.platform.auth.admin.AdminDtos.PermissionSummary;
import io.agentscope.platform.auth.admin.AdminDtos.RoleSummary;
import io.agentscope.platform.auth.admin.AdminDtos.RotatedSecret;
import io.agentscope.platform.auth.admin.AdminDtos.SetPermissions;
import io.agentscope.platform.auth.admin.AdminDtos.SetRoles;
import io.agentscope.platform.auth.admin.AdminDtos.UpdateClient;
import io.agentscope.platform.auth.admin.AdminDtos.UpdateRole;
import io.agentscope.platform.auth.admin.AdminDtos.UpdateUser;
import io.agentscope.platform.auth.admin.AdminDtos.UserSummary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理 API 的全部业务规则。
 *
 * <p>两条硬规则集中在这里，任何入口都绕不过：
 * 1. client secret 永不可读回（只有创建/轮换时明文返回一次）；
 * 2. 不能删掉或降级最后一个 ADMIN。
 *
 * <p>另外，改用户角色 / 改角色权限后会撤销受影响用户的全部授权，
 * 让"降权"在 token 过期前就生效（token 里带 role[]/perms[]，否则最多滞后 15 分钟）。
 */
@Service
public class AdminService {

    private static final Long TENANT_ID = 1L;
    private static final String ADMIN_ROLE = "ADMIN";

    private final JdbcClient jdbc;
    private final RegisteredClientRepository clients;
    private final OAuth2AuthorizationService authorizations;
    private final PasswordEncoder passwordEncoder;

    public AdminService(
            JdbcClient jdbc,
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.clients = clients;
        this.authorizations = authorizations;
        this.passwordEncoder = passwordEncoder;
    }

    // ==================== 用户 ====================

    public List<UserSummary> listUsers(String keyword) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        String sql = "SELECT u.id, u.username, u.display_name, u.status FROM `user` u";
        if (kw != null) {
            sql += " WHERE u.username LIKE :like OR u.display_name LIKE :like";
        }
        sql += " ORDER BY u.id";

        var spec = jdbc.sql(sql);
        if (kw != null) {
            spec = spec.param("like", "%" + kw + "%");
        }
        List<UserSummary> users = spec
                .query((rs, n) -> new UserSummary(
                        rs.getLong("id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("status"), List.of()))
                .list();
        return users.stream()
                .map(u -> new UserSummary(u.id(), u.username(), u.displayName(), u.status(), rolesOf(u.id())))
                .toList();
    }

    @Transactional
    public UserSummary createUser(CreateUser request) {
        if (request.username() == null || request.username().isBlank()) {
            throw AdminException.badRequest("用户名不能为空");
        }
        if (request.password() == null || request.password().length() < 6) {
            throw AdminException.badRequest("密码至少 6 位");
        }
        if (existsUser(request.username())) {
            throw AdminException.conflict("用户名已存在: " + request.username());
        }

        jdbc.sql("""
                        INSERT INTO `user` (tenant_id, username, password_hash, display_name, status)
                        VALUES (:tenantId, :username, :password, :displayName, 'ACTIVE')
                        """)
                .param("tenantId", TENANT_ID)
                .param("username", request.username())
                .param("password", passwordEncoder.encode(request.password()))
                .param("displayName", blankTo(request.displayName(), request.username()))
                .update();

        Long id = userIdOf(request.username());
        replaceUserRoles(id, request.roleIds());
        audit("user:create", "user", String.valueOf(id), request.username());
        return new UserSummary(id, request.username(), blankTo(request.displayName(), request.username()),
                "ACTIVE", rolesOf(id));
    }

    @Transactional
    public UserSummary updateUser(Long id, UpdateUser request) {
        Map<String, Object> current = loadUser(id);
        String status = request.status() == null ? (String) current.get("status") : request.status();
        if ("DISABLED".equals(status) && "ACTIVE".equals(current.get("status"))) {
            assertNotLastAdmin(id, "不能禁用最后一个管理员");
        }
        if (request.password() != null && !request.password().isBlank()) {
            jdbc.sql("UPDATE `user` SET password_hash = :p WHERE id = :id")
                    .param("p", passwordEncoder.encode(request.password())).param("id", id).update();
        }
        jdbc.sql("UPDATE `user` SET display_name = :d, status = :s WHERE id = :id")
                .param("d", request.displayName() == null ? current.get("display_name") : request.displayName())
                .param("s", status)
                .param("id", id)
                .update();

        if (!"ACTIVE".equals(status)) {
            revokeAll((String) current.get("username"));
        }
        audit("user:update", "user", String.valueOf(id), status);
        return new UserSummary(id, (String) current.get("username"),
                (String) (request.displayName() == null ? current.get("display_name") : request.displayName()),
                status, rolesOf(id));
    }

    @Transactional
    public UserSummary setUserRoles(Long id, SetRoles request) {
        Map<String, Object> current = loadUser(id);
        List<Long> roleIds = request.roleIds() == null ? List.of() : request.roleIds();
        boolean keepsAdmin = roleIds.stream().anyMatch(this::isAdminRole);
        if (!keepsAdmin) {
            assertNotLastAdmin(id, "不能移除最后一个管理员的 ADMIN 角色");
        }
        replaceUserRoles(id, roleIds);
        // 降权立即生效：撤掉该用户已签发的 token
        revokeAll((String) current.get("username"));
        audit("user:roles", "user", String.valueOf(id), roleIds.toString());
        return new UserSummary(id, (String) current.get("username"),
                (String) current.get("display_name"), (String) current.get("status"), rolesOf(id));
    }

    @Transactional
    public void deleteUser(Long id) {
        Map<String, Object> current = loadUser(id);
        assertNotLastAdmin(id, "不能禁用最后一个管理员");
        jdbc.sql("UPDATE `user` SET status = 'DISABLED' WHERE id = :id").param("id", id).update();
        revokeAll((String) current.get("username"));
        audit("user:disable", "user", String.valueOf(id), (String) current.get("username"));
    }

    // ==================== 角色 ====================

    public List<RoleSummary> listRoles() {
        Map<Long, List<String>> permissions = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT rp.role_id, p.code
                        FROM role_permission rp JOIN permission p ON p.id = rp.permission_id
                        ORDER BY p.code
                        """)
                .query((rs, n) -> Map.entry(rs.getLong("role_id"), rs.getString("code")))
                .list()
                .forEach(e -> permissions.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

        return jdbc.sql("""
                        SELECT r.id, r.code, r.name, r.description, r.built_in,
                               (SELECT COUNT(*) FROM user_role ur WHERE ur.role_id = r.id) AS user_count
                        FROM role r ORDER BY r.id
                        """)
                .query((rs, n) -> new RoleSummary(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("built_in"),
                        rs.getLong("user_count"),
                        permissions.getOrDefault(rs.getLong("id"), List.of())))
                .list();
    }

    @Transactional
    public RoleSummary createRole(CreateRole request) {
        if (request.code() == null || !request.code().matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw AdminException.badRequest("角色码必须是大写字母/数字/下划线，2-32 位");
        }
        jdbc.sql("""
                        INSERT INTO role (tenant_id, code, name, description, built_in)
                        VALUES (:tenantId, :code, :name, :description, 0)
                        """)
                .param("tenantId", TENANT_ID)
                .param("code", request.code())
                .param("name", blankTo(request.name(), request.code()))
                .param("description", request.description())
                .update();
        Long id = jdbc.sql("SELECT id FROM role WHERE tenant_id = :t AND code = :c")
                .param("t", TENANT_ID).param("c", request.code()).query(Long.class).single();
        replaceRolePermissions(id, request.permissionIds());
        audit("role:create", "role", String.valueOf(id), request.code());
        return listRoles().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public RoleSummary updateRole(Long id, UpdateRole request) {
        Map<String, Object> role = loadRole(id);
        jdbc.sql("UPDATE role SET name = :n, description = :d WHERE id = :id")
                .param("n", request.name() == null ? role.get("name") : request.name())
                .param("d", request.description() == null ? role.get("description") : request.description())
                .param("id", id)
                .update();
        audit("role:update", "role", String.valueOf(id), (String) role.get("code"));
        return listRoles().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteRole(Long id) {
        Map<String, Object> role = loadRole(id);
        if (Boolean.TRUE.equals(role.get("built_in"))) {
            throw AdminException.conflict("内置角色不可删除: " + role.get("code"));
        }
        // 无外键，关联必须显式清理，且要和主表删除在同一事务里
        jdbc.sql("DELETE FROM role_permission WHERE role_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM user_role WHERE role_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM role WHERE id = :id").param("id", id).update();
        audit("role:delete", "role", String.valueOf(id), (String) role.get("code"));
    }

    @Transactional
    public RoleSummary setRolePermissions(Long id, SetPermissions request) {
        Map<String, Object> role = loadRole(id);
        replaceRolePermissions(id, request.permissionIds());
        // 权限变更影响该角色下所有用户，逐个撤销 token
        jdbc.sql("SELECT u.username FROM `user` u JOIN user_role ur ON ur.user_id = u.id WHERE ur.role_id = :id")
                .param("id", id).query(String.class).list()
                .forEach(this::revokeAll);
        audit("role:permissions", "role", String.valueOf(id), role.get("code") + " -> " + request.permissionIds());
        return listRoles().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    public List<PermissionSummary> listPermissions() {
        return jdbc.sql("SELECT id, code, name, resource, action FROM permission ORDER BY code")
                .query((rs, n) -> new PermissionSummary(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("resource"), rs.getString("action")))
                .list();
    }

    // ==================== 客户端 ====================

    public List<ClientSummary> listClients() {
        return jdbc.sql("""
                        SELECT id, client_id, client_name, redirect_uris, scopes, client_settings
                        FROM oauth2_registered_client ORDER BY client_id
                        """)
                .query((rs, n) -> new ClientSummary(
                        rs.getString("id"),
                        rs.getString("client_id"),
                        rs.getString("client_name"),
                        splitCsv(rs.getString("redirect_uris")),
                        splitCsv(rs.getString("scopes")),
                        String.valueOf(rs.getString("client_settings")).contains("requireProofKey\":true")))
                .list();
    }

    @Transactional
    public RotatedSecret createClient(CreateClient request) {
        if (request.clientId() == null || request.clientId().isBlank()) {
            throw AdminException.badRequest("client_id 不能为空");
        }
        if (clients.findByClientId(request.clientId()) != null) {
            throw AdminException.conflict("client_id 已存在: " + request.clientId());
        }
        if (request.clientSecret() == null || request.clientSecret().length() < 8) {
            throw AdminException.badRequest("client secret 至少 8 位");
        }
        clients.save(baseClient(UUID.randomUUID().toString(), request.clientId())
                .clientSecret(passwordEncoder.encode(request.clientSecret()))
                .clientName(blankTo(request.clientName(), request.clientId()))
                .redirectUris(u -> u.addAll(orEmpty(request.redirectUris())))
                .scopes(s -> s.addAll(orDefaultScopes(request.scopes())))
                .build());
        audit("client:create", "client", request.clientId(), request.clientName());
        return new RotatedSecret(request.clientId(), request.clientSecret());
    }

    @Transactional
    public ClientSummary updateClient(String id, UpdateClient request) {
        RegisteredClient existing = clients.findById(id);
        if (existing == null) {
            throw AdminException.notFound("客户端不存在: " + id);
        }
        RegisteredClient.Builder builder = RegisteredClient.from(existing);
        if (request.clientName() != null) {
            builder.clientName(request.clientName());
        }
        if (request.redirectUris() != null) {
            builder.redirectUris(u -> {
                u.clear();
                u.addAll(request.redirectUris());
            });
        }
        if (request.scopes() != null) {
            builder.scopes(s -> {
                s.clear();
                s.addAll(request.scopes());
            });
        }
        clients.save(builder.build());
        audit("client:update", "client", id, existing.getClientId());
        return listClients().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public RotatedSecret rotateSecret(String id) {
        RegisteredClient existing = clients.findById(id);
        if (existing == null) {
            throw AdminException.notFound("客户端不存在: " + id);
        }
        String plain = UUID.randomUUID().toString().replace("-", "");
        clients.save(RegisteredClient.from(existing).clientSecret(passwordEncoder.encode(plain)).build());
        audit("client:rotate-secret", "client", id, existing.getClientId());
        return new RotatedSecret(existing.getClientId(), plain);
    }

    @Transactional
    public void deleteClient(String id) {
        RegisteredClient existing = clients.findById(id);
        if (existing == null) {
            throw AdminException.notFound("客户端不存在: " + id);
        }
        jdbc.sql("DELETE FROM oauth2_authorization WHERE registered_client_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = :id")
                .param("id", id).update();
        jdbc.sql("DELETE FROM oauth2_registered_client WHERE id = :id").param("id", id).update();
        audit("client:delete", "client", id, existing.getClientId());
    }

    // ==================== 内部 ====================

    private RegisteredClient.Builder baseClient(String id, String clientId) {
        return RegisteredClient.withId(id)
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                        .build());
    }

    private List<String> rolesOf(Long userId) {
        return jdbc.sql("""
                        SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id
                        WHERE ur.user_id = :id ORDER BY r.code
                        """)
                .param("id", userId).query(String.class).list();
    }

    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", userId).update();
        for (Long roleId : orEmpty(roleIds)) {
            jdbc.sql("INSERT INTO user_role (user_id, role_id) VALUES (:u, :r)")
                    .param("u", userId).param("r", roleId).update();
        }
    }

    private void replaceRolePermissions(Long roleId, List<Long> permissionIds) {
        jdbc.sql("DELETE FROM role_permission WHERE role_id = :id").param("id", roleId).update();
        for (Long permissionId : orEmpty(permissionIds)) {
            jdbc.sql("INSERT INTO role_permission (role_id, permission_id) VALUES (:r, :p)")
                    .param("r", roleId).param("p", permissionId).update();
        }
    }

    private boolean existsUser(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM `user` WHERE tenant_id = :t AND username = :u")
                .param("t", TENANT_ID).param("u", username).query(Long.class).single() > 0;
    }

    private Long userIdOf(String username) {
        return jdbc.sql("SELECT id FROM `user` WHERE tenant_id = :t AND username = :u")
                .param("t", TENANT_ID).param("u", username).query(Long.class).single();
    }

    private Map<String, Object> loadUser(Long id) {
        return jdbc.sql("SELECT id, username, display_name, status FROM `user` WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("username", rs.getString("username"));
                    m.put("display_name", rs.getString("display_name"));
                    m.put("status", rs.getString("status"));
                    return m;
                })
                .optional()
                .orElseThrow(() -> AdminException.notFound("用户不存在: " + id));
    }

    private Map<String, Object> loadRole(Long id) {
        return jdbc.sql("SELECT id, code, name, description, built_in FROM role WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("description", rs.getString("description"));
                    m.put("built_in", rs.getBoolean("built_in"));
                    return m;
                })
                .optional()
                .orElseThrow(() -> AdminException.notFound("角色不存在: " + id));
    }

    private boolean isAdminRole(Long roleId) {
        return jdbc.sql("SELECT code FROM role WHERE id = :id")
                .param("id", roleId).query(String.class).optional()
                .map(ADMIN_ROLE::equals).orElse(false);
    }

    private long activeAdminCount() {
        return jdbc.sql("""
                        SELECT COUNT(DISTINCT u.id) FROM `user` u
                        JOIN user_role ur ON ur.user_id = u.id
                        JOIN role r ON r.id = ur.role_id
                        WHERE r.code = :code AND u.status = 'ACTIVE'
                        """)
                .param("code", ADMIN_ROLE).query(Long.class).single();
    }

    private boolean isActiveAdmin(Long userId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM `user` u
                        JOIN user_role ur ON ur.user_id = u.id
                        JOIN role r ON r.id = ur.role_id
                        WHERE u.id = :id AND r.code = :code AND u.status = 'ACTIVE'
                        """)
                .param("id", userId).param("code", ADMIN_ROLE).query(Long.class).single() > 0;
    }

    private void assertNotLastAdmin(Long userId, String message) {
        if (isActiveAdmin(userId) && activeAdminCount() <= 1) {
            throw AdminException.conflict(message);
        }
    }

    /** 撤销该用户的全部授权：token 里带 role[]/perms[]，不撤销就要等它自然过期。 */
    private void revokeAll(String username) {
        List<String> ids = jdbc.sql("SELECT id FROM oauth2_authorization WHERE principal_name = :n")
                .param("n", username).query(String.class).list();
        for (String id : ids) {
            OAuth2Authorization authorization = authorizations.findById(id);
            if (authorization != null) {
                authorizations.remove(authorization);
            }
        }
    }

    private void audit(String action, String targetType, String targetId, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth == null ? "system" : auth.getName();
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, actor_name, action, target_type, target_id, detail, result)
                        VALUES (:t, :actor, :action, :type, :target, :detail, 'SUCCESS')
                        """)
                .param("t", TENANT_ID)
                .param("actor", actor)
                .param("action", action)
                .param("type", targetType)
                .param("target", targetId)
                .param("detail", detail == null ? null : truncate(detail, 1000))
                .update();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> orDefaultScopes(List<String> scopes) {
        return (scopes == null || scopes.isEmpty())
                ? List.of(OidcScopes.OPENID, OidcScopes.PROFILE)
                : scopes;
    }
}
