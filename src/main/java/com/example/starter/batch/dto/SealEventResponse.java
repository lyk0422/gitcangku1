package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 单个封箱事件的明细视图（含已作废封箱，历史证据只增不改）。
 *
 * @param version        封箱版本：首次封箱为 1；作废重封同一 sealKey 时递增
 * @param labelNo        标签号
 * @param quantity       封箱数量（正整数）；作废后该数量已释放，不计入 sealedQuantity
 * @param status         事件状态：SEALED（活跃占用中）/ VOIDED（已作废释放）
 * @param voidReason     作废原因；未作废时为 null（null 表示该封箱从未作废）
 * @param sealedAt       封箱时间，ISO-8601 UTC instant
 * @param voidedAt       作废时间，ISO-8601 UTC instant；未作废时为 null
 */
public record SealEventResponse(
        String sealKey,
        int version,
        long labelNo,
        long quantity,
        String status,
        String voidReason,
        Instant sealedAt,
        Instant voidedAt
) {
}
