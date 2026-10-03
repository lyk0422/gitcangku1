package com.example.starter.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与可选的结构化错误明细（如放行校核的违例列表）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final transient Object details;

    public ApiException(HttpStatus status, String message, Object details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public Object details() {
        return details;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, null);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message, null);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, null);
    }

    public static ApiException unprocessable(String message, Object details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message, details);
    }
}