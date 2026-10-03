package com.example.starter.dive.service;

/**
 * 幂等冲突：同一requestId携带了与首次不同的参数，映射HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}