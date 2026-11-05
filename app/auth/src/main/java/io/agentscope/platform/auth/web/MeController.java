package io.agentscope.platform.auth.web;

import io.agentscope.platform.auth.rbac.RbacUserDetailsService;
import io.agentscope.platform.auth.rbac.UserAccount;
import io.agentscope.platform.auth.rbac.UserRepository;
import io.agentscope.platform.common.ApiResult;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录者是谁、有哪些角色与权限。
 *
 * <p>权限从库里取，而不是读 {@code authentication.getAuthorities()}：
 * 后者混着 Spring Security 的内部 authority（例如 {@code FACTOR_PASSWORD}），
 * 当成业务权限返回会让前端菜单出现幽灵项。库里取到的与写进 token 的也是同一份。
 */
@RestController
public class MeController {

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/auth/me")
    public ApiResult<MeResponse> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResult.failure("未登录", "UNAUTHENTICATED");
        }
        return users.findByUsername(RbacUserDetailsService.DEFAULT_TENANT, authentication.getName())
                .map(account -> ApiResult.ok(new MeResponse(
                        account.username(), account.roles(), account.permissions())))
                .orElseGet(() -> ApiResult.failure("用户不存在", "NOT_FOUND"));
    }

    public record MeResponse(String username, List<String> roles, List<String> permissions) {}
}
