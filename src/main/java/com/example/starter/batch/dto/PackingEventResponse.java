package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 标签核销历史事件（只读证据，后续操作不可覆盖）。
 * type=PACKED 表示封箱（quantity 为该箱数量），occurredAt 取 packedAt；
 * type=VOIDED 表示作废（quantity 为释放数量），occurredAt 取 voidedAt。
 * 时间均为 UTC instant。
 */
public record PackingEventResponse(
        long packingId,
        String type,
        long labelNo,
        int quantity,
        String actorId,
        Instant occurredAt
) {
}
