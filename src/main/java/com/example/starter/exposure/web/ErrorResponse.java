package com.example.starter.exposure.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status                    HTTP 状态码
 * @param error                     错误类型
 * @param message                   错误信息（不包含堆栈），数量类冲突给出实际值与要求值
 * @param requestId                 触发错误的幂等键；无则为 null
 * @param timestamp                 错误发生时刻，epoch 毫秒，UTC
 * @param nextAvailableHourStartUtc 活动小时预算耗尽时给出的下一可用小时起点
 *                                  （epoch 毫秒，UTC，左闭右开）；不适用或为 null 时不出现在响应中
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        long timestamp,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long nextAvailableHourStartUtc
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), null);
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         Long nextAvailableHourStartUtc) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), nextAvailableHourStartUtc);
    }
}
