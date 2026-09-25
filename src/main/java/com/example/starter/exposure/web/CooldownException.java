package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

/**
 * 冷却期拦截异常：HTTP 429，并携带冷却结束的 UTC 时刻（epoch 毫秒）。
 */
public class CooldownException extends ApiException {

    private final long cooldownUntilUtc;

    public CooldownException(String message, long cooldownUntilUtc) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.cooldownUntilUtc = cooldownUntilUtc;
    }

    public long getCooldownUntilUtc() {
        return cooldownUntilUtc;
    }
}
