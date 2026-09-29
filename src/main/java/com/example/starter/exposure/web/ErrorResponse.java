package com.example.starter.exposure.web;

import java.time.Instant;

/**
 * 统一错误响应体。历史字段保持不变，机器可区分的诊断字段追加在末尾，
 * 不适用时为 null。
 *
 * @param status             HTTP 状态码
 * @param error              错误类型
 * @param message            错误信息（不包含堆栈）
 * @param requestId          触发错误的幂等键；无则为 null
 * @param timestamp          错误发生时刻，epoch 毫秒，UTC
 * @param reason             机器可区分原因码（如 ACTIVITY_HOUR_EXHAUSTED）；无则为 null
 * @param actualValue        实际值（如当前已占用名额）；无则为 null
 * @param requiredValue      要求值（如额度上限或本次申请要求数）；无则为 null
 * @param differenceValue    差额（要求值 - 实际值，缺额为负）；无则为 null
 * @param nextAvailableAtUtc 下一可用时刻（下一有剩余名额的自然小时起点），epoch 毫秒 UTC；无则为 null
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        long timestamp,
        String reason,
        Long actualValue,
        Long requiredValue,
        Long differenceValue,
        Long nextAvailableAtUtc
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(),
                null, null, null, null, null);
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         String reason, Long actualValue, Long requiredValue,
                         Long differenceValue, Long nextAvailableAtUtc) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(),
                reason, actualValue, requiredValue, differenceValue, nextAvailableAtUtc);
    }
}
