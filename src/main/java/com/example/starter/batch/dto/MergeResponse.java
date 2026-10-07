package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。requiredMaterialCount 为父批不同物料数（要求覆盖数），
 * coveredMaterialCount 为实际被绑定覆盖的物料数，missingMaterialCount 为差额（成功时为 0）。
 */
public record MergeResponse(
        String childBatchKey,
        BatchStatus childStatus,
        String productCode,
        List<MergeParent> parents,
        int requiredMaterialCount,
        int coveredMaterialCount,
        int missingMaterialCount,
        Instant mergedAt
) {

    /**
     * 合批父批概要：合批后状态落定 MERGED。
     */
    public record MergeParent(
            String batchKey,
            String productCode,
            BatchStatus status
    ) {
    }
}
