package com.example.starter.domain;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与机器可读原因码，由全局异常处理器转换为响应体。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    public ApiException(HttpStatus status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public static ApiException unprocessable(String reason, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, reason, message);
    }

    public static ApiException conflict(String reason, String message) {
        return new ApiException(HttpStatus.CONFLICT, reason, message);
    }

    public static ApiException notFound(String reason, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, reason, message);
    }
}