package io.agentscope.platform.auth.web;

import io.agentscope.platform.common.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下发 CSRF 令牌。
 *
 * <p>控制台与 auth 同源、用 session 认证，写请求必须带 CSRF 令牌。
 * SPA 登录后取一次，放在 `X-CSRF-TOKEN` 头里。
 */
@RestController
public class CsrfController {

    @GetMapping("/auth/csrf")
    public ApiResult<Map<String, String>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return ApiResult.failure("CSRF 未启用", "CSRF_DISABLED");
        }
        return ApiResult.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }
}
