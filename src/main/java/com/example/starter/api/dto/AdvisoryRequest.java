package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 漏洞公告创建/更新请求：漏洞编号、受影响制品精确坐标与严重级别；
 * expiresAt 为公告失效时刻（UTC），null 表示永久有效。
 */
public record AdvisoryRequest(
        @NotBlank String vulnerabilityId,
        @NotBlank String artifactName,
        @Positive int artifactVersion,
        @NotBlank String severity,
        Instant expiresAt) {
}
