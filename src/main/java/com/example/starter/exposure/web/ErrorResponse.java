package com.example.starter.exposure.web;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体。历史字段（status/error/message/requestId/timestamp）保持稳定，
 * 新增 {@code reason}（可机器区分的原因码）与 {@code details}（实际值、要求值或差额等明细）；
 * 不适用时两者为 null，保持 null 语义稳定。
 *
 * @param status    HTTP 状态码
 * @param error     错误类型
 * @param message   错误信息（不包含堆栈）
 * @param requestId 触发错误的幂等键；无则为 null
 * @param timestamp 错误发生时刻，epoch 毫秒，UTC
 * @param reason    可区分原因码，无则为 null
 * @param details   结构化明细，无则为 null
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
}
