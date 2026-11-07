package io.agentscope.platform.server.security;

import io.agentscope.platform.common.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下发 CSRF 令牌。
 *
 * <p>为什么不依赖 `CookieCsrfTokenRepository` 自动写 cookie：Spring Security 6+ 的 CSRF 令牌是
 * 延迟的，只有被真正读取时才写 cookie。前端读不到 cookie 就不会带 `X-XSRF-TOKEN`，
 * 所有写请求都会被 403——这个坑只在真浏览器里才会暴露。
 */
@RestController
public class CsrfController {

    @GetMapping("/api/csrf")
    public ApiResult<Map<String, String>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return ApiResult.failure("CSRF 未启用", "CSRF_DISABLED");
        }
        return ApiResult.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }
}
