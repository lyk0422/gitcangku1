package com.example.starter.exposure.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status            HTTP 状态码
 * @param error             错误类型
 * @param message           错误信息（不包含堆栈）
 * @param requestId         触发错误的幂等键；无则为 null
 * @param timestamp         错误发生时刻，epoch 毫秒，UTC
 * @param cooldownUntilUtc  冷却结束的 UTC 时刻，epoch 毫秒；仅冷却期未过的 429 携带，其余错误省略
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        long timestamp,
        Long cooldownUntilUtc
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), null);
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         Long cooldownUntilUtc) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), cooldownUntilUtc);
    }
}
