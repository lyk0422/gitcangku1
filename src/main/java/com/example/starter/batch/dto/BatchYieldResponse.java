package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

/**
 * 批次产率查询响应。yield 为该批次产率记录，未登记时为 null；
 * recallBlock 为召回阻断原因（批次自身被召回或其血缘闭包内祖先被召回），无阻断为 null。
 */
public record BatchYieldResponse(
        String batchKey,
        BatchStatus status,
        YieldEntryResponse yield,
        RecallBlock recallBlock
) {

    /**
     * 召回阻断原因。direct 为 true 表示批次自身被直接召回；
     * false 表示血缘闭包内祖先被召回。已登记的产率记录不删除，仅阻断新增/修订。
     */
    public record RecallBlock(
            String recalledBatchKey,
            String reason,
            boolean direct
    ) {
    }
}
