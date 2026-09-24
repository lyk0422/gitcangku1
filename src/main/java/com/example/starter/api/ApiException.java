package com.example.starter.api;

import org.springframework.http.HttpStatus;

/**
 * 业务错误，携带返回给客户端的 HTTP 状态码与错误码。
 */
public class ApiException extends RuntimeException {

    /** 错误码，例如 ZONE_NOT_FOUND / VERSION_CONFLICT / IDEMPOTENT_PARAM_MISMATCH。 */
    private final String code;
    /** 映射的 HTTP 状态。 */
    private final HttpStatus status;
    /** 可选的结构化错误明细（如 422  时逐候选命中集合），null 表示无。 */
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

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Object details() {
        return details;
    }
}
