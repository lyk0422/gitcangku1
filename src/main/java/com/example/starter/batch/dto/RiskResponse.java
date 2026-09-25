package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次过敏原风险视图：已放行批次发现新增过敏原进入 ALLERGEN_RISK 时产生。
 * resolved 为 true 表示已由重新检验加双角色放行解除；releaseSnapshot 保留原放行快照。
 */
public record RiskResponse(
        String batchKey,
        BatchStatus batchStatus,
        int detectedVersion,
        List<String> newAllergenCodes,
        ReleaseSnapshot releaseSnapshot,
        boolean resolved,
        Instant detectedAt,
        Instant resolvedAt
) {

    /**
     * 原放行快照：进入风险时的成分版本、放行时间与两名不同角色批准人。
     */
    public record ReleaseSnapshot(
            int componentVersion,
            Instant releasedAt,
            String qualityApprover,
            String operationsApprover
    ) {
    }
}
