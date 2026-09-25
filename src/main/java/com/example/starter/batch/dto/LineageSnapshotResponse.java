package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 血缘快照响应：批次当前成分版本、跨拆分与合批的传递祖先集合、直接合批来源。
 */
public record LineageSnapshotResponse(
        String batchKey,
        BatchStatus status,
        CompositionVersionResponse currentComposition,
        List<LineageEntryResponse> ancestors,
        List<MergeSourceEntry> mergeSources
) {

    /**
     * 直接合批来源：来源批次、目标容器与合批顺序。
     */
    public record MergeSourceEntry(
            String sourceKey,
            String containerKey,
            int seq
    ) {
    }
}
