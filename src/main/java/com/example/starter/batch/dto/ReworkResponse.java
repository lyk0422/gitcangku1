package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 返工重投响应。原批次状态落定 REWORKED（终态）；新返工批次初始 QUARANTINED，
 * 代次为原批次代次加一，继承产品编码、生产 UTC 时间与必做检验项，
 * 但不继承任何检验结论与批准，须重新执行全部必做检验与双角色放行。reworkAt 为 UTC instant。
 */
public record ReworkResponse(
        String reworkKey,
        String sourceBatchKey,
        BatchStatus sourceStatus,
        String reworkBatchKey,
        String reworkBatchNo,
        int generation,
        List<String> requiredTests,
        String reason,
        Instant reworkAt
) {
}
