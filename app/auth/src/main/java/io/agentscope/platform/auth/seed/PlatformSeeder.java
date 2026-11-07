package io.agentscope.platform.auth.seed;

import io.agentscope.platform.auth.config.AuthProperties;
import io.agentscope.platform.auth.rbac.UserRepository;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等的启动种子：管理员账号 + 工作台客户端。
 *
 * <p>放在代码里而不是 Flyway SQL 里，理由有两条：
 * 1. 密码要在运行时用配置的编码器哈希，不能把哈希写进迁移脚本；
 * 2. RegisteredClient 的 client_settings / token_settings 是 SAS 的 JSON 格式，
 *    手写 SQL 极易和框架序列化漂移，交给 builder 最稳。
 *
 * <p>这不违反"客户端只能由管理员预先登记"——种子等价于一次离线登记，
 * 且动态注册端点始终不暴露。
 */
@Component
public class PlatformSeeder implements ApplicationRunner {

    private static final Long DEFAULT_TENANT_ID = 1L;
    private static final Long ADMIN_ROLE_ID = 1L;

    private final UserRepository users;
    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties props;
    private final TransactionTemplate tx;

    public PlatformSeeder(
            UserRepository users,
            RegisteredClientRepository clients,
            PasswordEncoder passwordEncoder,
            AuthProperties props,
            TransactionTemplate tx) {
        this.users = users;
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        seedWorkbenchClient();
    }

    private void seedAdmin() {
        AuthProperties.BootstrapAdmin admin = props.bootstrapAdmin();
        tx.executeWithoutResult(status -> {
            if (!users.existsByUsername(DEFAULT_TENANT_ID, admin.username())) {
                users.insertUser(
                        DEFAULT_TENANT_ID,
                        admin.username(),
                        passwordEncoder.encode(admin.password()),
                        admin.displayName());
                // 无外键，用户与角色绑定必须在同一个事务里显式完成
                users.bindRole(admin.username(), ADMIN_ROLE_ID);
            }
        });
    }

    private void seedWorkbenchClient() {
        AuthProperties.WorkbenchClient wb = props.workbenchClient();
        List<String> redirectUris = Arrays.stream(wb.redirectUris().split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        RegisteredClient existing = clients.findByClientId(wb.clientId());
        if (existing != null) {
            // 已存在则只同步回调地址：开发（5173）与容器（8080）的回调不同，
            // 不同步的话换一种启动方式就会 invalid_redirect_uri。
            clients.save(RegisteredClient.from(existing)
                    .redirectUris(uris -> {
                        uris.clear();
                        uris.addAll(redirectUris);
                    })
                    .build());
            return;
        }
        clients.save(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(wb.clientId())
                .clientSecret(passwordEncoder.encode(wb.clientSecret()))
                .clientName("Agent 工作台")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(redirectUris))
                .postLogoutRedirectUri("http://localhost:8088/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        // 所有客户端强制 PKCE，含机密客户端
                        .requireProofKey(true)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        // 每次刷新换发新 refresh token，旧的立即失效
                        .reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                        .build())
                .build());
    }
}
