package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。parentCount 为实际父批数（实际值），requiredParentMin 为要求的最小父批数（要求值）；
 * 新批初始 QUARANTINED，继承全部父批必做检验项的并集（按父批顺序去重）。
 */
public record MergeBatchesResponse(
        String batchKey,
        String batchNo,
        String productCode,
        BatchStatus status,
        int parentCount,
        int requiredParentMin,
        List<MergeParent> parents,
        int boundDeclarationCount,
        List<String> requiredTests,
        Instant createdAt
) {

    /**
     * 合批父批视图：父批业务键与其物料代码。
     */
    public record MergeParent(
            String batchKey,
            String productCode
    ) {
    }
}