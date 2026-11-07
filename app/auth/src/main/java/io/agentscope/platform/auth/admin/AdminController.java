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
import io.agentscope.platform.common.ApiResult;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理 API。
 *
 * <p>只接受 session 认证（默认 filter chain 没有配置 Bearer 解析），
 * 每个端点按权限点授权；这样"拿用户 token 调管理接口"这条路不存在。
 */
@RestController
@RequestMapping("/auth/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    // ---------- 用户 ----------

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResult<List<UserSummary>> listUsers(@RequestParam(required = false) String keyword) {
        return ApiResult.ok(service.listUsers(keyword));
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResult<UserSummary> createUser(@RequestBody CreateUser request) {
        return ApiResult.ok(service.createUser(request));
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResult<UserSummary> updateUser(@PathVariable Long id, @RequestBody UpdateUser request) {
        return ApiResult.ok(service.updateUser(id, request));
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('role:assign')")
    public ApiResult<UserSummary> setRoles(@PathVariable Long id, @RequestBody SetRoles request) {
        return ApiResult.ok(service.setUserRoles(id, request));
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    public ApiResult<Void> deleteUser(@PathVariable Long id) {
        service.deleteUser(id);
        return ApiResult.ok(null);
    }

    // ---------- 角色 ----------

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResult<List<RoleSummary>> listRoles() {
        return ApiResult.ok(service.listRoles());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResult<RoleSummary> createRole(@RequestBody CreateRole request) {
        return ApiResult.ok(service.createRole(request));
    }

    @PatchMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResult<RoleSummary> updateRole(@PathVariable Long id, @RequestBody UpdateRole request) {
        return ApiResult.ok(service.updateRole(id, request));
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResult<RoleSummary> setPermissions(@PathVariable Long id, @RequestBody SetPermissions request) {
        return ApiResult.ok(service.setRolePermissions(id, request));
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:delete')")
    public ApiResult<Void> deleteRole(@PathVariable Long id) {
        service.deleteRole(id);
        return ApiResult.ok(null);
    }

    // ---------- 权限 ----------

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('permission:read')")
    public ApiResult<List<PermissionSummary>> listPermissions() {
        return ApiResult.ok(service.listPermissions());
    }

    // ---------- 客户端 ----------

    @GetMapping("/clients")
    @PreAuthorize("hasAuthority('client:read')")
    public ApiResult<List<ClientSummary>> listClients() {
        return ApiResult.ok(service.listClients());
    }

    @PostMapping("/clients")
    @PreAuthorize("hasAuthority('client:write')")
    public ApiResult<RotatedSecret> createClient(@RequestBody CreateClient request) {
        return ApiResult.ok(service.createClient(request));
    }

    @PatchMapping("/clients/{id}")
    @PreAuthorize("hasAuthority('client:write')")
    public ApiResult<ClientSummary> updateClient(@PathVariable String id, @RequestBody UpdateClient request) {
        return ApiResult.ok(service.updateClient(id, request));
    }

    @PostMapping("/clients/{id}/rotate-secret")
    @PreAuthorize("hasAuthority('client:rotate-secret')")
    public ApiResult<RotatedSecret> rotateSecret(@PathVariable String id) {
        return ApiResult.ok(service.rotateSecret(id));
    }

    @DeleteMapping("/clients/{id}")
    @PreAuthorize("hasAuthority('client:delete')")
    public ApiResult<Void> deleteClient(@PathVariable String id) {
        service.deleteClient(id);
        return ApiResult.ok(null);
    }
}
