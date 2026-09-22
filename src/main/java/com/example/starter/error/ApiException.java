package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带 HTTP 状态与稳定错误码，由全局异常处理器转换为响应体。
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
