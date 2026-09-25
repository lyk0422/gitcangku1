package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

/**
 * 冷却期未过导致的 429 异常：额外携带冷却结束的 UTC 时刻（epoch 毫秒），
 * 供客户端在响应体中读取 retryAfter 信息；不使用 Retry-After 头承载时刻。
 */
public class CooldownNotElapsedException extends ApiException {

    private final long cooldownUntilUtc;

    public CooldownNotElapsedException(String message, long cooldownUntilUtc) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.cooldownUntilUtc = cooldownUntilUtc;
    }

    public long getCooldownUntilUtc() {
        return cooldownUntilUtc;
    }
}
