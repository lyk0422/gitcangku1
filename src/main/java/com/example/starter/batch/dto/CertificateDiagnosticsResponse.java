package com.example.starter.batch.dto;

import com.example.starter.batch.DeclarationStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合格证匹配诊断：对指定物料与批号，在给定时点逐个评估声明版本。
 * 按 declarationKey、version 升序稳定排序；只读，不改变任何状态。
 */
public record CertificateDiagnosticsResponse(
        String materialCode,
        String batchNo,
        Instant evaluatedAt,
        int totalCount,
        int eligibleCount,
        List<CandidateView> candidates
) {

    /**
     * 单个声明版本的诊断结果。eligible 为 true 时 reason 为 null；
     * 否则 reason 为 WITHDRAWN/PREFIX_MISMATCH/NOT_YET_VALID/EXPIRED 之一。
     * matchedPrefix 为按批号匹配到的最高优先级前缀，无匹配为 null。
     */
    public record CandidateView(
            String declarationKey,
            int version,
            DeclarationStatus status,
            boolean eligible,
            String reason,
            String matchedPrefix,
            Instant validFrom,
            Instant validTo
    ) {
    }
}