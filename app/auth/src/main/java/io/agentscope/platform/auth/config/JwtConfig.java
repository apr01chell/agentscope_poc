package io.agentscope.platform.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/** 授权服务器的持久化、签名密钥与设置。 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    /**
     * 从 PEM 读 RSA 私钥，再推导出公钥组成 JWK。
     *
     * <p>不用启动时随机生成：那样每次重启所有 token 立即失效，多副本之间也验不过签。
     * 只用 JDK 的标准 API 解析 PKCS#8，不引入额外的 PEM 解析库。
     */
    @Bean
    JWKSource<SecurityContext> jwkSource(AuthProperties props, ResourceLoader loader, Environment env)
            throws Exception {
        String path = props.jwk().path();

        // 生产环境必须显式提供外部密钥；用仓库内的开发密钥启动生产是绝不能放行的。
        boolean production = java.util.Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equals("prod") || p.equals("production"));
        if (production && (path == null || path.startsWith("classpath:"))) {
            throw new IllegalStateException(
                    "生产环境必须通过 AUTH_JWK_PATH 指定外部签名密钥，禁止使用仓库内的开发密钥");
        }

        Resource resource = loader.getResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("签名密钥不存在: " + path);
        }

        RSAKey rsaKey;
        try (InputStream in = resource.getInputStream()) {
            rsaKey = parsePkcs8(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private static RSAKey parsePkcs8(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);

        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey =
                (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("auth-signing-key-1")
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }
}
