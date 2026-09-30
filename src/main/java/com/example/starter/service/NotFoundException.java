package com.example.starter.service;

/**
 * 资源不存在，映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    /**
     * @param message 错误描述
     */
    public NotFoundException(String message) {
        super(message);
    }
}
