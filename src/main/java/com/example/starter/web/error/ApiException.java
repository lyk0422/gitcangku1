package com.example.starter.web.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类，携带对应的 HTTP 状态码与稳定错误码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
