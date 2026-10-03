package com.example.starter.exception;

/**
 * 幂等冲突异常（HTTP 409）：同一 requestId 携带不同参数重发。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}