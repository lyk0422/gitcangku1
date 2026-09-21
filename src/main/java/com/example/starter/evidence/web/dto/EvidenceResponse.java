package com.example.starter.evidence.web.dto;

import com.example.starter.evidence.domain.Evidence;
import com.example.starter.evidence.domain.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 证物视图。
 */
public record EvidenceResponse(
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        EvidenceStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static EvidenceResponse from(Evidence evidence) {
        return new EvidenceResponse(
                evidence.evidenceKey(),
                evidence.caseKey(),
                evidence.category(),
                evidence.sealNo(),
                evidence.custodianId(),
                evidence.status(),
                evidence.createdAt(),
                evidence.updatedAt());
    }
}
