package com.example.starter.exposure.web;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体。
 *
 * @param status    HTTP 状态码
 * @param error     错误类型
 * @param message   错误信息（不包含堆栈）
 * @param requestId 触发错误的幂等键；无则为 null
 * @param details   结构化错误明细（如小时预算耗尽时的下一可用小时）；无明细为 null
 * @param timestamp 错误发生时刻，epoch 毫秒，UTC
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        Map<String, Object> details,
        long timestamp
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, null, Instant.now().toEpochMilli());
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         Map<String, Object> details) {
        this(status, error, message, requestId, details, Instant.now().toEpochMilli());
    }
}
