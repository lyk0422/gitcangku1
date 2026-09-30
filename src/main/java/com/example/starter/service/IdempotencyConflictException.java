package com.example.starter.service;

/**
 * 同一 requestId 重放但请求参数与首次不一致，映射 HTTP 409。
 */
public class IdempotencyConflictException extends RuntimeException {

    /**
     * @param message 错误描述
     */
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
