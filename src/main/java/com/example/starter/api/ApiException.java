package com.example.starter.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 业务错误，携带返回给客户端的 HTTP 状态码与错误码；
 * 可附带结构化详情（如速度越界的航段号与未舍入速度）。
 */
public class ApiException extends RuntimeException {

    /** 错误码，例如 ZONE_NOT_FOUND / VERSION_CONFLICT / SEGMENT_SPEED_OUT_OF_RANGE。 */
    private final String code;
    /** 映射的 HTTP 状态。 */
    private final HttpStatus status;
    /** 结构化错误详情，随响应体一并返回；无详情为 null。 */
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

    public Map<String, Object> details() {
        return details;
    }
}