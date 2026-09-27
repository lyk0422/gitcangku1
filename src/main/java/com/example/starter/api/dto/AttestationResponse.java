package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 见证提交/撤销结果视图。
 *
 * @param activeAttestations   当前有效见证人数（实际值）
 * @param requiredAttestations 锁定要求的见证人数（要求值）
 * @param missingAttestations  距锁定要求尚缺的见证人数（差额，不为负）
 * @param repositoryVersion    该次写操作完成后的仓库版本号
 */
public record AttestationResponse(
        String name,
        int version,
        String operator,
        String digest,
        boolean revoked,
        Instant attestedAt,
        Instant revokedAt,
        int activeAttestations,
        int requiredAttestations,
        int missingAttestations,
        long repositoryVersion) {
}
