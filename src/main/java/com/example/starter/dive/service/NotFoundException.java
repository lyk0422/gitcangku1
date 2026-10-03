package com.example.starter.dive.service;

/**
 * 资源不存在，映射HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}