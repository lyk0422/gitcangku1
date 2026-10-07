package com.example.starter.certificate.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次合格证匹配诊断响应：对全部声明版本逐一评估，按 声明键、版本号 稳定排序；
 * evaluatedAt 为评估时点（UTC），validAtEvaluatedAt 按左闭右开区间解释。
 */
public record CertificateDiagnosticsResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant evaluatedAt,
        List<Candidate> candidates
) {
    /**
     * 单条声明版本的匹配评估；eligible 为全部条件同时满足；reason 给出首个不满足条件的原因或 OK。
     */
    public record Candidate(
            String declarationKey,
            int version,
            String status,
            boolean materialMatched,
            boolean prefixMatched,
            boolean active,
            boolean validAtEvaluatedAt,
            boolean eligible,
            String reason
    ) {
    }
}
