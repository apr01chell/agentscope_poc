package io.agentscope.platform.server.security;

import io.agentscope.platform.common.ApiResult;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 前端用它判断"我登录了吗、我是谁、能做什么"。 */
@RestController
public class MeController {

    @GetMapping("/api/me")
    public ApiResult<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResult.failure("未登录", "UNAUTHENTICATED");
        }
        if (authentication.getPrincipal() instanceof OidcUser oidc) {
            return ApiResult.ok(Map.of(
                    "sub", oidc.getSubject(),
                    "name", String.valueOf(oidc.getClaims().getOrDefault("name", oidc.getSubject())),
                    "tenant", String.valueOf(oidc.getClaims().getOrDefault("tenant", "default")),
                    "roles", listClaim(oidc.getClaims().get("role")),
                    "perms", listClaim(oidc.getClaims().get("perms"))));
        }
        return ApiResult.ok(Map.of(
                "sub", authentication.getName(),
                "name", authentication.getName(),
                "tenant", "default",
                "roles", List.of(),
                "perms", List.of()));
    }

    private static List<String> listClaim(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
