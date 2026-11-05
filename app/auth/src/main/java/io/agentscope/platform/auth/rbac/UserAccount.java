package io.agentscope.platform.auth.rbac;

import java.util.List;

/** 一个用户的完整认证/授权视图：基本信息 + 角色码 + 权限码。 */
public record UserAccount(
        Long id,
        String tenantCode,
        String username,
        String passwordHash,
        String displayName,
        String status,
        List<String> roles,
        List<String> permissions) {

    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
