package io.agentscope.platform.auth.admin;

import io.agentscope.platform.common.ApiResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 只处理 {@link AdminException}。
 *
 * <p>刻意不写兜底的 {@code @ExceptionHandler(Exception.class)}：
 * 那会把 Spring Security 的 AccessDeniedException 也吞掉，403 变成 500。
 */
@RestControllerAdvice
public class AdminExceptionHandler {

    @ExceptionHandler(AdminException.class)
    public ResponseEntity<ApiResult<Void>> handle(AdminException error) {
        return ResponseEntity.status(error.status())
                .body(ApiResult.failure(error.getMessage(), error.status().name()));
    }
}
