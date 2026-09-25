package com.example.starter.batch.dto;

import java.util.List;

/**
 * 合批兼容诊断响应。mergeable 为全部来源可合并且容器兼容矩阵覆盖所有不同级别对；
 * missingCompatibilityPairs 列出容器未声明的不同隔离级别对（形如 SEGREGATED×ISOLATED）。
 */
public record MergeDiagnoseResponse(
        String containerKey,
        boolean mergeable,
        List<String> distinctSegregationLevels,
        List<String> missingCompatibilityPairs,
        List<String> unionAllergenCodes,
        List<SourceDiagnosis> sources
) {

    /**
     * 单来源诊断：eligible 为 false 时 reason 给出可区分原因。
     */
    public record SourceDiagnosis(
            String batchKey,
            boolean exists,
            String status,
            String segregationLevel,
            boolean currentVersionTested,
            String recalledAncestor,
            boolean eligible,
            String reason
    ) {
    }
}
