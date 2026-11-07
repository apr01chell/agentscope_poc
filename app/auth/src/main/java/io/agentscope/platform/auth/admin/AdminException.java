package io.agentscope.platform.auth.admin;

import org.springframework.http.HttpStatus;

/** 管理 API 的业务异常：带 HTTP 状态，由 {@link AdminExceptionHandler} 统一转成信封。 */
public class AdminException extends RuntimeException {

    private final HttpStatus status;

    public AdminException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static AdminException badRequest(String message) {
        return new AdminException(HttpStatus.BAD_REQUEST, message);
    }

    public static AdminException notFound(String message) {
        return new AdminException(HttpStatus.NOT_FOUND, message);
    }

    public static AdminException conflict(String message) {
        return new AdminException(HttpStatus.CONFLICT, message);
    }
}
