package com.example.starter.exposure.web;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status    HTTP 状态码
 * @param error     错误类型
 * @param code      机器可读原因码，可区分失败原因；无则为 null
 * @param message   错误信息（不包含堆栈）
 * @param requestId 触发错误的幂等键；无则为 null
 * @param timestamp 错误发生时刻，epoch 毫秒，UTC
 */
public record ErrorResponse(
        int status,
        String error,
        String code,
        String message,
        String requestId,
        long timestamp
) {
    public ErrorResponse(int status, String error, String code, String message, String requestId) {
        this(status, error, code, message, requestId, Instant.now().toEpochMilli());
    }
}
