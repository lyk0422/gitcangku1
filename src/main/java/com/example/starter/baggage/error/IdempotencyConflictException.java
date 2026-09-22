package com.example.starter.baggage.error;

/**
 * 幂等冲突（HTTP 409）：同一 requestId 携带不同参数重复提交。
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
