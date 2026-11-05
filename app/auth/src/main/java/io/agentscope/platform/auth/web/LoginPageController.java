package io.agentscope.platform.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 过渡期的最简登录页。
 *
 * <p>直接输出 HTML 而不引模板引擎；CSRF token 从请求属性里取（CsrfFilter 会放进去），
 * 否则表单登录后会被 403 拦掉。工单 B/C 上线后由控制台 SPA 的 /login 路由取代。
 */
@Controller
public class LoginPageController {

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String login(HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String hidden = csrfToken == null
                ? ""
                : "<input type=\"hidden\" name=\"" + csrfToken.getParameterName()
                        + "\" value=\"" + csrfToken.getToken() + "\">";
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><title>登录 - 用户中心</title></head>
                <body style="font-family:system-ui;max-width:320px;margin:10vh auto">
                  <h1>用户中心</h1>
                  <form method="post" action="/auth/login">
                    <p><label>用户名<br><input name="username" autocomplete="username" style="width:100%%"></label></p>
                    <p><label>密码<br><input name="password" type="password" autocomplete="current-password" style="width:100%%"></label></p>
                    %s
                    <button type="submit">登录</button>
                  </form>
                </body>
                </html>
                """.formatted(hidden);
    }
}
