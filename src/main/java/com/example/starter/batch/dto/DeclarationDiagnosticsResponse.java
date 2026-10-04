package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 合格证匹配诊断响应：对给定物料、批号与时点（UTC，左闭右开）逐个声明版本给出
 * 是否可匹配及原因。matchedDeclarationKey/matchedVersion 为最终命中的声明，
 * 无有效命中或存在同优先级歧义时为 null，ambiguous 标识歧义。
 */
public record DeclarationDiagnosticsResponse(
        String materialCode,
        String batchNo,
        Instant at,
        String matchedDeclarationKey,
        Integer matchedVersion,
        boolean ambiguous,
        List<Candidate> candidates
) {

    /**
     * 单个声明版本的诊断项：各匹配条件独立给出，reason 为不满足时的原因（满足时为 null）。
     */
    public record Candidate(
            String declarationKey,
            int version,
            int priority,
            String status,
            boolean prefixMatched,
            boolean withinValidity,
            boolean active,
            boolean eligible,
            String reason
    ) {
    }
}