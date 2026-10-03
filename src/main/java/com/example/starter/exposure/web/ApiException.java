package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * API 业务异常基类，携带对应的 HTTP 状态码与可选的结构化明细。
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

    /** 结构化错误明细（如额度实际值/要求值/差额、下一可用小时）；无则为 null。 */
    public Map<String, Object> getDetails() {
        return details;
    }
}
