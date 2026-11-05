package io.agentscope.platform.auth.rbac;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * RBAC 读写入口。
 *
 * <p>全库无外键，关联靠应用层维护：这里的查询都显式 join，不依赖数据库帮我们约束。
 * 角色与权限都是"一次查全"，避免签 token 时逐条查询。
 */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String tenantCode, String username) {
        Optional<UserAccountRow> row = jdbc.sql("""
                        SELECT u.id, u.username, u.password_hash, u.display_name, u.status,
                               t.code AS tenant_code
                        FROM `user` u
                        JOIN tenant t ON t.id = u.tenant_id
                        WHERE t.code = :tenantCode AND u.username = :username
                        """)
                .param("tenantCode", tenantCode)
                .param("username", username)
                .query((rs, n) -> new UserAccountRow(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        rs.getString("tenant_code")))
                .optional();

        return row.map(r -> new UserAccount(
                r.id(), r.tenantCode(), r.username(), r.passwordHash(),
                r.displayName(), r.status(), findRoles(r.id()), findPermissions(r.id())));
    }

    public List<String> findRoles(Long userId) {
        return jdbc.sql("""
                        SELECT r.code
                        FROM user_role ur
                        JOIN role r ON r.id = ur.role_id
                        WHERE ur.user_id = :userId
                        ORDER BY r.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    public List<String> findPermissions(Long userId) {
        return jdbc.sql("""
                        SELECT DISTINCT p.code
                        FROM user_role ur
                        JOIN role_permission rp ON rp.role_id = ur.role_id
                        JOIN permission p ON p.id = rp.permission_id
                        WHERE ur.user_id = :userId
                        ORDER BY p.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    public boolean existsByUsername(Long tenantId, String username) {
        return jdbc.sql("SELECT COUNT(*) FROM `user` WHERE tenant_id = :tenantId AND username = :username")
                .param("tenantId", tenantId)
                .param("username", username)
                .query(Integer.class)
                .single() > 0;
    }

    /** 无外键，所以插入用户与绑定角色必须在同一个事务里由调用方显式完成。 */
    public void insertUser(Long tenantId, String username, String encodedPassword, String displayName) {
        jdbc.sql("""
                        INSERT INTO `user` (tenant_id, username, password_hash, display_name, status)
                        VALUES (:tenantId, :username, :password, :displayName, 'ACTIVE')
                        """)
                .param("tenantId", tenantId)
                .param("username", username)
                .param("password", encodedPassword)
                .param("displayName", displayName)
                .update();
    }

    public void bindRole(String username, Long roleId) {
        jdbc.sql("""
                        INSERT INTO user_role (user_id, role_id)
                        SELECT u.id, :roleId FROM `user` u WHERE u.username = :username
                        """)
                .param("roleId", roleId)
                .param("username", username)
                .update();
    }

    private record UserAccountRow(
            Long id, String username, String passwordHash, String displayName,
            String status, String tenantCode) {}
}
