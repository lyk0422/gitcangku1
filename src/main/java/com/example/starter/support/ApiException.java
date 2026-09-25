package com.example.starter.support;

/**
 * 业务 API 异常，携带 HTTP 状态码、错误码与可选结构化明细。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Object details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(422, "UNPROCESSABLE_ENTITY", message);
    }

    /** 422 并携带稳定的结构化命中路径明细。 */
    public static ApiException unprocessable(String message, Object details) {
        return new ApiException(422, "UNPROCESSABLE_ENTITY", message, details);
    }
}
