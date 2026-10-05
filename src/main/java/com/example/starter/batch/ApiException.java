package com.example.starter.batch;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的 HTTP 状态（400 参数非法、404 不存在、409 状态/键冲突、422 前置条件未满足）。
 * details 为可选结构化明细（如配方不达时各成分差额），随错误响应一并返回。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
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

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECONDITION_FAILED", message);
    }

    /**
     * 422 且携带结构化明细（如各成分差额）。
     */
    public static ApiException unprocessable(String message, Object details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRECONDITION_FAILED", message, details);
    }
}
