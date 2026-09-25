package com.example.starter.exposure.web;

import java.time.Instant;

/**
 * 冷却期拦截错误响应体：在统一错误字段之外携带冷却结束的 UTC 时刻。
 *
 * @param status           HTTP 状态码（429）
 * @param error            错误类型
 * @param message          错误信息（不包含堆栈）
 * @param cooldownUntilUtc 冷却结束时刻，epoch 毫秒，UTC
 * @param timestamp        错误发生时刻，epoch 毫秒，UTC
 */
public record CooldownErrorResponse(
        int status,
        String error,
        String message,
        long cooldownUntilUtc,
        long timestamp
) {
    public CooldownErrorResponse(int status, String error, String message, long cooldownUntilUtc) {
        this(status, error, message, cooldownUntilUtc, Instant.now().toEpochMilli());
    }
}
