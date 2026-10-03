package com.example.starter.aging.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态、消息与结构化明细（如缺失日期列表）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final transient Object details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Object details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public Object details() {
        return details;
    }
}