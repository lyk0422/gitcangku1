package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。子批初始 QUARANTINED；frozenAllergens/frozenCleanGeneration 为子批固化的
 * 过敏原集合与清洁代次依据，取自合批落定瞬间全部父批的共同值，事后不随父批或产线清洁改写。
 * mergedAt 为 UTC instant。
 */
public record MergeResponse(
        String childBatchKey,
        String childBatchNo,
        BatchStatus childStatus,
        List<String> parentBatchKeys,
        List<String> frozenAllergens,
        int frozenCleanGeneration,
        List<ParentBasis> parents,
        Instant mergedAt
) {

    /**
     * 参与合批的单个父批在合批落定时的过敏原与清洁代次快照（冻结依据明细）。
     */
    public record ParentBasis(
            String batchKey,
            List<String> allergens,
            int cleanGeneration,
            BatchStatus status
    ) {
    }
}
