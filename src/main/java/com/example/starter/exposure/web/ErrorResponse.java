package com.example.starter.exposure.web;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status             HTTP 状态码
 * @param error              错误类型
 * @param message            错误信息（不含堆栈），数量类错误含实际值与要求值
 * @param requestId          触发错误的幂等键；无则为 null
 * @param timestamp          错误发生时刻，epoch 毫秒，UTC
 * @param nextAvailableHour  小时额度耗尽时给出的下一可用小时（UTC，0~23）；无可用小时或不适用为 null
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        long timestamp,
        Integer nextAvailableHour
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), null);
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         Integer nextAvailableHour) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), nextAvailableHour);
    }
}