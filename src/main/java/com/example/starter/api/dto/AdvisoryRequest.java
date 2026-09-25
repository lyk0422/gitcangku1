package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 漏洞公告写入请求：一条公告命中一个受影响制品坐标，可重复提交以更新严重级别与到期时刻。
 *
 * @param vulnerabilityId 漏洞编号（全局唯一标识，如 CVE 编号）
 * @param artifactName    受影响制品名称（精确坐标）
 * @param artifactVersion 受影响制品精确版本号，正整数
 * @param severity        严重级别：CRITICAL/HIGH/MEDIUM/LOW，仅未过期 CRITICAL 参与发布门禁
 * @param expiresAt       公告 UTC 到期时刻；该时刻之后公告不再命中
 */
public record AdvisoryRequest(
        @NotBlank String vulnerabilityId,
        @NotBlank String artifactName,
        @Positive int artifactVersion,
        @NotBlank String severity,
        @NotNull Instant expiresAt) {
}
