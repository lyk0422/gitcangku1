package com.example.starter.aging;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态、消息与结构化细节（如缺失日期列表），由全局处理器转为响应。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> details;

    public BusinessException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public BusinessException(HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }
}