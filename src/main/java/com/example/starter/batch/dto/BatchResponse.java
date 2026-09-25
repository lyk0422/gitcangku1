package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt/validUntil 为 UTC instant。
 * shelfLifeMinutes 为不可改写的保质分钟；validUntil 为当前有效期（含已确认延期顺延）；
 * expired 表示服务端当前时刻是否已达到有效期；remainingMinutes 为距到期剩余分钟
 * （未到期向下取整的非负剩余整分钟，到期后为 0）。到期不改写批次状态。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        long shelfLifeMinutes,
        Instant validUntil,
        boolean expired,
        long remainingMinutes,
        List<String> requiredTests,
        Instant createdAt
) {
}
