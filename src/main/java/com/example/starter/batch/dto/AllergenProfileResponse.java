package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次过敏原/清洁代次视图：展示批次自身的规范化过敏原集合与清洁代次；
 * 若该批次是合批子批，frozenBasis 给出合批时冻结的依据与参与父批（非合批子批为 null）。
 */
public record AllergenProfileResponse(
        String batchKey,
        BatchStatus status,
        List<String> allergens,
        Integer cleaningGeneration,
        FrozenMergeBasis frozenBasis
) {

    /**
     * 合批冻结依据（仅合批子批存在）：合批时全部父批共有的过敏原集合、共同清洁代次、
     * 参与合批的父批业务键（按规范化顺序），以及合批 UTC instant；创建后不可改写。
     */
    public record FrozenMergeBasis(
            List<String> allergens,
            Integer cleaningGeneration,
            List<String> parentBatchKeys,
            Instant mergedAt
    ) {
    }
}
