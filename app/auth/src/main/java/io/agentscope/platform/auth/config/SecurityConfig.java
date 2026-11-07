package io.agentscope.platform.auth.config;

import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
@EnableWebSecurity
// 管理 API 用 hasAuthority('user:read') 这类权限点注解，需要方法级鉴权
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Spring Security 7.1.2 里 configurer 已移到 spring-security-config 包，
     * 且没有静态 authorisationServer() 工厂方法，用无参构造。
     */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            Function<OidcUserInfoAuthenticationContext, OidcUserInfo> userInfoMapper)
            throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();

        http.securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, server -> server
                        // OIDC：userinfo 由我们自己映射，才能带上 tenant / role[] / perms[]
                        .oidc(oidc -> oidc.userInfoEndpoint(endpoint -> endpoint.userInfoMapper(userInfoMapper))))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/.well-known/**", "/oauth2/jwks").permitAll()
                        .anyRequest().authenticated())
                // 协议端点（/userinfo、/oauth2/**）必须以 401 + JSON 失败，不能跳 HTML 登录页：
                // 调用方是机器（例如工作台的 OAuth2 客户端），拿到一个 302 到登录页会被判成
                // invalid_user_info_response，整条登录流程就断在这里。
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new OrRequestMatcher(
                                        PathPatternRequestMatcher.pathPattern("/userinfo"),
                                        PathPatternRequestMatcher.pathPattern("/oauth2/token"),
                                        PathPatternRequestMatcher.pathPattern("/oauth2/introspect"),
                                        PathPatternRequestMatcher.pathPattern("/oauth2/revoke")))
                        // 注意不能包含 /oauth2/authorize：那是浏览器端点，未登录时必须跳登录页
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        // 健康检查必须匿名可达：compose 用它探活，浏览器也用它判断后端是否在线
                        .requestMatchers("/api/health", "/actuator/health", "/error", "/favicon.ico").permitAll()
                        // 登录前就要拿 CSRF 令牌，否则表单提交会被 403 拦掉
                        .requestMatchers("/auth/csrf").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/auth/login")
                        .permitAll());
        return http.build();
    }

    /**
     * 用 DelegatingPasswordEncoder：用户密码和 client secret 都以 {bcrypt} 前缀入库，
     * 同一个编码器同时服务表单登录与 SAS 的客户端认证。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
