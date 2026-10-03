package com.example.starter.service;

/** 业务冲突，映射HTTP 409。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}