package com.example.starter.water;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态、可区分的错误码与可选结构化明细。
 * 400 参数非法、404 不存在、409 操作人/状态/幂等/已开始小时冲突、422 配额或逐小时上限不足。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** 结构化错误明细（如逐小时超限列表、批量失败序号），无明细时为 null。 */
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

    public static ApiException badRequest(String code, String message, Object details) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, details);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException notFound(String code, String message, Object details) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message, details);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException conflict(String code, String message, Object details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }

    public static ApiException unprocessable(String code, String message, Object details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }

    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED", message);
    }
}
