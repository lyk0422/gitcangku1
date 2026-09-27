package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量封箱响应：本次创建的封箱版本明细（按标签号规范升序）及提交后该批次的累计值。
 * packedQuantity 为全部有效封箱数量之和（单位：件），usedLabelCount 为号段内已用标签数，
 * plannedQuantity 为计划数量，remainingQuantity 为差额（计划-已封），均为实际值。
 */
public record PackResponse(
        String batchKey,
        List<Entry> packed,
        int packedQuantity,
        int usedLabelCount,
        int plannedQuantity,
        int remainingQuantity,
        Instant packedAt
) {

    /**
     * 本次封箱的单条版本明细。packingId 即封箱版本号。
     */
    public record Entry(
            long packingId,
            long labelNo,
            int quantity,
            String packedBy,
            Instant packedAt
    ) {
    }
}
