package io.agentscope.platform.server.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * 当前请求的身份。
 *
 * <p>从 OIDC 的 id token 里取 `sub` 与 `tenant`；非 OIDC 主体（例如测试里注入的）退化为
 * "用户名 + default 租户"，这样测试不必伪造整个 id token。
 */
@Component
public class CurrentUser {

    public String sub() {
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new IllegalStateException("未认证");
        }
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            return oidc.getSubject();
        }
        return auth.getName();
    }

    public String tenant() {
        Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OidcUser oidc) {
            String tenant = oidc.getClaimAsString("tenant");
            if (tenant != null && !tenant.isBlank()) {
                return tenant;
            }
        }
        return "default";
    }
}
