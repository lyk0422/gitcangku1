package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本摘要与见证明细视图。
 *
 * <p>null 语义：未登记摘要时 algorithm/digest/registeredBy/registeredAt 为 null，
 * attestations 为空列表；见证记录的 revokedAt 未撤销时为 null。
 *
 * @param activeAttestations   当前有效见证人数（实际值）
 * @param requiredAttestations 锁定要求的见证人数（要求值）
 * @param missingAttestations  距锁定要求尚缺的见证人数（差额，不为负）
 */
public record ChecksumDetailResponse(
        String name,
        int version,
        boolean withdrawn,
        String algorithm,
        String digest,
        String registeredBy,
        Instant registeredAt,
        List<AttestationView> attestations,
        int activeAttestations,
        int requiredAttestations,
        int missingAttestations) {
}
