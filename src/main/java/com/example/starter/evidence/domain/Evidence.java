package com.example.starter.evidence.domain;

import java.time.LocalDateTime;

/**
 * 证物主表行。业务字段（evidenceKey/caseKey/category/sealNo）入库后不可修改；
 * custodianId 仅在交接接受时原子切换；status 见 {@link EvidenceStatus}。
 */
public record Evidence(
        Long id,
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        EvidenceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
