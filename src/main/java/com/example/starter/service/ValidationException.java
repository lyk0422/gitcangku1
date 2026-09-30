package com.example.starter.service;

/**
 * 语义校验失败，映射 HTTP 422。
 */
public class ValidationException extends ApiException {

    public ValidationException(String code, String message) {
        super(code, message);
    }
}
