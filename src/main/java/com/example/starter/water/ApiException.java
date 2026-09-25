package com.example.starter.water;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与可区分的错误码。
 * 400 参数非法、404 不存在、409 操作人/状态/幂等冲突、422 业务前置条件不满足。
 * details 可选，携带机器可读细节（如冲突时段、最近可用时段），无细节时为 null。
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

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException conflict(String code, String message, Object details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }

    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED", message);
    }

    public static ApiException unprocessable(String code, String message, Object details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}
