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
 * @param timestamp 错误发生时刻，epoch 毫秒，UTC
 * @param details   可区分原因的结构化明细（如额度实际值/要求值/差额、下一可用小时）；
 *                  无则为 null
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        long timestamp,
        Map<String, Object> details
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), null);
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         Map<String, Object> details) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), details);
    }
}
