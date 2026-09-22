package com.example.starter.baggage.error;

/**
 * 业务规则冲突（HTTP 422）：如航段非 OPEN、版本不匹配、行李状态不满足、清单不一致等。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
