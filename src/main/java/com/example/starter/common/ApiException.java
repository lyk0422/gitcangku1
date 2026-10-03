package com.example.starter.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与可区分的拒绝原因码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorReason reason;

    public ApiException(HttpStatus status, ErrorReason reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorReason reason() {
        return reason;
    }

    /**
     * 构造 409 冲突异常。
     */
    public static ApiException conflict(ErrorReason reason, String message) {
        return new ApiException(HttpStatus.CONFLICT, reason, message);
    }

    /**
     * 构造 404 异常。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorReason.NOT_FOUND, message);
    }

    /**
     * 构造 422 异常。
     */
    public static ApiException unprocessable(ErrorReason reason, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, reason, message);
    }
}