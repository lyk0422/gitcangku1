package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 返工重投响应。原批次落定 REWORKED 终态；返工批次代次为原批次代次加一，
 * 初始 QUARANTINED，继承产品编码、生产 UTC 时间与必做检验项，不继承检验结论与批准记录。
 * reworkedAt 为 UTC instant。
 */
public record ReworkResponse(
        String reworkKey,
        String originBatchKey,
        BatchStatus originStatus,
        ReworkBatch reworkBatch,
        String reworkReason,
        Instant reworkedAt
) {

    /**
     * 返工产生的新批次概要，携带返工代次（从 1 开始，上限 3）。
     */
    public record ReworkBatch(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            int generation,
            List<String> requiredTests,
            Instant createdAt
    ) {
    }
}
