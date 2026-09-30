package com.example.starter.service;

/**
 * 业务拒绝异常基类，携带机器可读错误码。
 */
public class ApiException extends RuntimeException {

    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
