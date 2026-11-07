package io.agentscope.platform.auth.oidc;

import io.agentscope.platform.auth.rbac.RbacUserDetailsService;
import io.agentscope.platform.auth.rbac.UserAccount;
import io.agentscope.platform.auth.rbac.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * 把 RBAC 的身份信息写进 token 与 userinfo。
 *
 * <p>用 {@code authorization.getPrincipalName()} 反查用户，而不是读 {@code context.getPrincipal()}：
 * 授权码换 token 时 principal 是客户端认证对象，不是用户；从 authorization 里取最稳。
 */
@Configuration
public class PlatformClaims {

    private static final OAuth2TokenType ID_TOKEN = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

    private final UserRepository repository;

    public PlatformClaims(UserRepository repository) {
        this.repository = repository;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> platformTokenCustomizer() {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                    && !ID_TOKEN.equals(context.getTokenType())) {
                return;
            }
            if (context.getAuthorization() == null) {
                return;
            }
            Optional<UserAccount> account = repository.findByUsername(
                    RbacUserDetailsService.DEFAULT_TENANT, context.getAuthorization().getPrincipalName());
            if (account.isEmpty()) {
                return;
            }
            UserAccount user = account.get();
            // sub 必须与 /userinfo 返回的 sub 一致，否则 OIDC 客户端会判定
            // invalid_user_info_response（Spring 会比对两者）。统一用 user.id。
            context.getClaims().subject(String.valueOf(user.id()));
            context.getClaims().claims(claims -> {
                claims.put("tenant", user.tenantCode());
                claims.put("role", user.roles());
                claims.put("perms", user.permissions());
            });
        };
    }

    @Bean
    Function<OidcUserInfoAuthenticationContext, OidcUserInfo> platformUserInfoMapper() {
        return context -> {
            String principal = context.getAuthorization().getPrincipalName();
            Optional<UserAccount> account =
                    repository.findByUsername(RbacUserDetailsService.DEFAULT_TENANT, principal);

            return account.map(user -> OidcUserInfo.builder()
                            .subject(String.valueOf(user.id()))
                            .name(user.displayName())
                            .claim("tenant", user.tenantCode())
                            .claim("role", user.roles())
                            .claim("perms", user.permissions())
                            .build())
                    .orElseGet(() -> OidcUserInfo.builder()
                            .subject(principal)
                            .name(principal)
                            .claim("tenant", RbacUserDetailsService.DEFAULT_TENANT)
                            .claim("role", List.of())
                            .claim("perms", List.of())
                            .build());
        };
    }
}
