package com.example.starter.exception;

/**
 * 资源不存在异常（HTTP 404）。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}