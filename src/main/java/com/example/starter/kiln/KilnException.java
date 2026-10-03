package com.example.starter.kiln;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态、错误码与结构化细节（如违规段号与异常时刻）。
 */
public class KilnException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public KilnException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public KilnException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}