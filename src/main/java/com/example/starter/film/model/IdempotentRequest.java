package com.example.starter.film.model;

import java.time.LocalDateTime;

/**
 * 幂等请求记录：同一 (requestId, operation) 首次受理时固化参数摘要。
 *
 * @param requestId   请求ID
 * @param operation   操作类型：SEGMENT_SUBMIT / REVIEW_CREATE / DELIVERY
 * @param filmId      所属影片ID；无影片上下文时为 {@code null}
 * @param payloadHash 规范化参数的 SHA-256 摘要
 * @param resultRef   首次成功结果引用（版次行ID/审核ID/回执ID）；未完成时为 {@code null}
 * @param createdAt   首次受理时间，Asia/Shanghai
 */
public record IdempotentRequest(
        String requestId,
        String operation,
        Long filmId,
        String payloadHash,
        String resultRef,
        LocalDateTime createdAt
) {
}
