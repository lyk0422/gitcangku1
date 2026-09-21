package com.example.starter.evidence.web.dto;

import com.example.starter.evidence.domain.SealCheck;
import com.example.starter.evidence.domain.SealCheckResult;

import java.time.LocalDateTime;

/**
 * 封条核验记录视图。
 */
public record SealCheckResponse(
        Long id,
        String evidenceKey,
        String actorId,
        SealCheckResult result,
        String detail,
        LocalDateTime createdAt
) {
    public static SealCheckResponse from(SealCheck check, String evidenceKey) {
        return new SealCheckResponse(
                check.id(),
                evidenceKey,
                check.actorId(),
                check.result(),
                check.detail(),
                check.createdAt());
    }
}
