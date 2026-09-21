package com.example.starter.curtailment.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与可区分的错误码。
 * 语义约定：400 参数非法、404 资源不存在、409 版本或状态冲突、422 容量不足。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException capacity(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CAPACITY_EXCEEDED", message);
    }
}
