package com.example.starter.service;

/**
 * 资源不存在，映射 HTTP 404。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
