package com.example.starter.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 业务错误，携带返回给客户端的 HTTP 状态码与错误码。
 */
public class ApiException extends RuntimeException {

    /** 错误码，例如 ZONE_NOT_FOUND / VERSION_CONFLICT / IDEMPOTENT_PARAM_MISMATCH。 */
    private final String code;
    /** 映射的 HTTP 状态。 */
    private final HttpStatus status;
    /** 附加错误细节（如 429 时的当前占用数），可为 null。 */
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
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

    /** 附加错误细节；无附加信息时返回 null。 */
    public Map<String, Object> details() {
        return details;
    }
}
