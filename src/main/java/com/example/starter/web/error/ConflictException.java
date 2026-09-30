package com.example.starter.web.error;

import org.springframework.http.HttpStatus;

/**
 * 409：状态冲突（时段未结束、幂等键异参重放、重复入账等）。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
