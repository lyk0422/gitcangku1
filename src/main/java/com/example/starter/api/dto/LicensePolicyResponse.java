package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 许可证策略视图。
 */
public record LicensePolicyResponse(
        long id,
        String scopeType,
        Long lockFileId,
        String artifactName,
        Integer artifactVersion,
        String noticeType,
        String textKey,
        Integer textVersion,
        Instant createdAt) {
}
