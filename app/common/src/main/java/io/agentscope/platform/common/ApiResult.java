package io.agentscope.platform.common;

/**
 * 统一响应信封。
 *
 * <p>与前端 {@code src/types/api.ts} 的 {@code ApiResult} 一一对应：前端用 {@code unwrap}
 * 取出 {@code data}，因此失败响应里 {@code data} 恒为 {@code null}，成功响应里 {@code message} 恒为 {@code null}。
 *
 * <p>放在 common 模块而不是各服务各写一份：这是两个服务共享的线上契约，复制两份必然漂移。
 */
public record ApiResult<T>(boolean success, String message, T data, String code) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, null, data, null);
    }

    public static <T> ApiResult<T> failure(String message, String code) {
        return new ApiResult<>(false, message, null, code);
    }
}
