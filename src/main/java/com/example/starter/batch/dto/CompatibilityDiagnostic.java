package com.example.starter.batch.dto;

import com.example.starter.batch.SegregationLevel;

import java.util.List;

/**
 * 合批兼容诊断：在不执行合批的前提下给出目标容器与全部来源按最终血缘集合的校验结论。
 * compatible 为 true 时可以合批；为 false 时 reasons 列出每个可区分的阻断原因，
 * blockedPairs 列出缺少兼容矩阵声明的级别对。
 */
public record CompatibilityDiagnostic(
        String targetBatchKey,
        boolean compatible,
        List<String> reasons,
        List<BlockedPair> blockedPairs,
        SegregationLevel targetLevel,
        List<SourceProfile> sources
) {

    /**
     * 缺少兼容声明的级别对（按级别严格度规范化为 lowLevel/highLevel）。
     */
    public record BlockedPair(
            String sourceBatchKey,
            SegregationLevel lowLevel,
            SegregationLevel highLevel
    ) {
    }

    /**
     * 来源画像：最终成分版本、过敏原代码、隔离级别、检验与库存状态。
     */
    public record SourceProfile(
            String batchKey,
            int componentVersion,
            List<String> allergenCodes,
            SegregationLevel segregationLevel,
            boolean testedAtCurrentVersion,
            java.math.BigDecimal quantity
    ) {
    }
}
