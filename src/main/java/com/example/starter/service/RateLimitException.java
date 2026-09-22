package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 额度已满（公告当日总额度或访客当日额度），对应 HTTP 429。
 */
public class RateLimitException extends ApiException {

    public RateLimitException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }
}
