package com.example.starter.salinity.exception;

/**
 * 资源不存在异常，映射 HTTP 404。
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * @param message 面向调用方的错误说明
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
