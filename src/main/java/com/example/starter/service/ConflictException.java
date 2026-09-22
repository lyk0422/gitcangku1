package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 状态冲突：非法状态迁移或幂等键同键异参，对应 HTTP 409。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
