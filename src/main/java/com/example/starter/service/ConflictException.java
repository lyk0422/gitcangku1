package com.example.starter.service;

/**
 * 业务冲突异常，映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}