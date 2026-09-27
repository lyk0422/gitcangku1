package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码；details 为可选的结构化错误明细
 * （如小时预算耗尽时的下一可用小时），无明细为 null。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
