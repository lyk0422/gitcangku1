package com.example.starter.spectrum.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 成功频率方案的不可变持久化记录。
 */
public record PlanRecord(
        Long id,
        String networkId,
        String planKey,
        String requestId,
        int versionFrom,
        int versionTo,
        String normalizedRequest,
        String beforeConfig,
        String afterConfig,
        String interferenceSummary,
        OffsetDateTime createdAt
) {
}
