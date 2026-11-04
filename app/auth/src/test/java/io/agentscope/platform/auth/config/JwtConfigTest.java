package io.agentscope.platform.auth.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

/** 签名密钥外部化：生产环境不允许用仓库内的开发密钥。 */
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();
    private final DefaultResourceLoader loader = new DefaultResourceLoader();

    private static AuthProperties props(String path) {
        return new AuthProperties(
                new AuthProperties.Jwk(path),
                new AuthProperties.BootstrapAdmin("admin", "admin123", "管理员"),
                new AuthProperties.WorkbenchClient("workbench", "secret", "http://localhost/cb"));
    }

    @Test
    void productionProfileRefusesClasspathKey() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.jwkSource(props("classpath:keys/dev-rsa.pem"), loader, prod));
        assertTrue(error.getMessage().contains("AUTH_JWK_PATH"), "错误信息应指明该配什么");
    }

    @Test
    void devProfileLoadsClasspathKey() throws Exception {
        MockEnvironment dev = new MockEnvironment();
        assertTrue(config.jwkSource(props("classpath:keys/dev-rsa.pem"), loader, dev) != null);
    }

    @Test
    void missingKeyFileFailsFast() {
        MockEnvironment dev = new MockEnvironment();
        assertThrows(IllegalStateException.class,
                () -> config.jwkSource(props("classpath:keys/does-not-exist.pem"), loader, dev));
    }
}
