package com.example.starter.blackstart;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码与稳定错误码，由全局异常处理器转换为响应体。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * HTTP 状态码。
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 稳定错误码，如 PLAN_NOT_FOUND、REQUEST_ID_CONFLICT。
     */
    public String code() {
        return code;
    }
}