package io.agentscope.platform.auth.admin;

import java.util.List;

/** 管理 API 的请求/响应契约。用嵌套 record 集中放，避免几十个单字段文件。 */
public final class AdminDtos {

    private AdminDtos() {}

    // ---------- 用户 ----------

    public record UserSummary(
            Long id, String username, String displayName, String status, List<String> roles) {}

    public record CreateUser(String username, String password, String displayName, List<Long> roleIds) {}

    public record UpdateUser(String displayName, String password, String status) {}

    public record SetRoles(List<Long> roleIds) {}

    // ---------- 角色 ----------

    public record RoleSummary(
            Long id, String code, String name, String description,
            boolean builtIn, long userCount, List<String> permissions) {}

    public record CreateRole(String code, String name, String description, List<Long> permissionIds) {}

    public record UpdateRole(String name, String description) {}

    public record SetPermissions(List<Long> permissionIds) {}

    // ---------- 权限 ----------

    public record PermissionSummary(Long id, String code, String name, String resource, String action) {}

    // ---------- 客户端 ----------

    public record ClientSummary(
            String id, String clientId, String clientName,
            List<String> redirectUris, List<String> scopes, boolean requireProofKey) {}

    public record CreateClient(
            String clientId, String clientSecret, String clientName,
            List<String> redirectUris, List<String> scopes) {}

    public record UpdateClient(String clientName, List<String> redirectUris, List<String> scopes) {}

    /** secret 只在创建/轮换时返回一次，之后不可读回。 */
    public record RotatedSecret(String clientId, String clientSecret) {}
}
