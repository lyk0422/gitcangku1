package com.example.starter.exposure.web;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体。历史字段（status/error/message/requestId/timestamp）保持稳定，
 * 新增可空 reason/details 置于末尾：老消费方不受影响，新消费方可据此区分失败原因、
 * 读取实际值、要求值或差额。
 *
 * @param status    HTTP 状态码
 * @param error     错误类型
 * @param message   错误信息（不包含堆栈）
 * @param requestId 触发错误的幂等键；无则为 null
 * @param timestamp 错误发生时刻，epoch 毫秒，UTC
 * @param reason    可机读原因码（如 HOUR_BUDGET_EXHAUSTED）；无细分原因为 null
 * @param details   诊断明细（actual/required/difference/nextAvailableHour 等）；无明细为 null
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        String requestId,
        long timestamp,
        String reason,
        Map<String, Object> details
) {
    public ErrorResponse(int status, String error, String message, String requestId) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), null, null);
    }

    public ErrorResponse(int status, String error, String message, String requestId,
                         String reason, Map<String, Object> details) {
        this(status, error, message, requestId, Instant.now().toEpochMilli(), reason, details);
    }
}
