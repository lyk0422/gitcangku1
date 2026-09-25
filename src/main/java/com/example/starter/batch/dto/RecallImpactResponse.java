package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 血缘影响查询响应：根召回批次、召回代次与状态，以及最终血缘闭包内
 * 每个受影响批次的自身状态与合格复检进度。
 */
public record RecallImpactResponse(
        String batchKey,
        int recallVersion,
        String recallStatus,
        List<ImpactEntry> affected
) {

    /**
     * 单个受影响批次：自身状态、是否已完成合格复检、仍缺合格复检的必做检验项。
     */
    public record ImpactEntry(
            String batchKey,
            BatchStatus status,
            boolean reinspectionComplete,
            List<String> missingItems
    ) {
    }
}
