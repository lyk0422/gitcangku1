package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带可区分的 HTTP 状态码：
 * 400 参数非法；403 不具备对应案件权限；404 不存在；409 操作人不匹配或状态冲突（含幂等键改参与令版本失效）；
 * 422 证物状态前置条件失败（不属来源案件、借出未归还、封签异常、已在其他移交中）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
