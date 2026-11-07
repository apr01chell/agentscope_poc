package io.agentscope.platform.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 登录成功后跳回的站点根地址。
     *
     * <p>开发时前端在 vite（5173）、后端在 8080，是跨 origin 的；生产由 nginx 同源，
     * 这个值留空即可。做成配置而不是硬编码，是因为它随部署形态变化。
     */
    @org.springframework.beans.factory.annotation.Value("${app.web-origin:http://localhost:5173}")
    private String webOrigin;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/actuator/**").permitAll()
                        // 业务接口要登录；其余（SPA 静态资源、登录流程）放行
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2Login(login -> login.defaultSuccessUrl(webOrigin + "/", true))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                // 前端是 SPA，走 session + cookie：CSRF 令牌放 cookie 里，
                // axios 默认会读 XSRF-TOKEN 并回填 X-XSRF-TOKEN 头。
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                // 未登录访问 /api/** 返回 401（而不是 302 到登录页），前端才能区分"要登录"和"出错"
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.pathPattern("/api/**")));
        return http.build();
    }
}
