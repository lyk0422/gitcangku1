package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转为统一错误响应。
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BizException(HttpStatus status, String code, String message) {
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

    public static BizException notFound(String message) {
        return new BizException(HttpStatus.NOT_FOUND, "BOTTLE_NOT_FOUND", message);
    }

    public static BizException badRequest(String code, String message) {
        return new BizException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(HttpStatus.CONFLICT, code, message);
    }

    public static BizException unprocessable(String code, String message) {
        return new BizException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}