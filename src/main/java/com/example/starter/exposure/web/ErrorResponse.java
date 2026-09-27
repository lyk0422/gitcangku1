package com.example.starter.exposure.web;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status                     HTTP 状态码
 * @param error                      错误类型
 * @param message                    错误信息（不包含堆栈）
 * @param requestId                  触发错误的幂等键；无则为 null
 * @param timestamp                  错误发生时刻，epoch 毫秒，UTC
 * @param nextAvailableHourStartUtc  活动小时额度不可用时，下一可用小时起始时刻（epoch 毫秒，UTC）；
 *                                   不适用或无可用小时为 null
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        long timestamp,
        Long nextAvailableHourStartUtc
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), null);
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         Long nextAvailableHourStartUtc) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), nextAvailableHourStartUtc);
    }
}
