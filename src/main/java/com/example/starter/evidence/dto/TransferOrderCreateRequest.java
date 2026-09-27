package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 移交令签发请求。签发人（X-Actor-Id）须具备来源案件保管权限；
 * 有效期按 UTC 左闭右开解释，validUntil 须晚于 validFrom。
 *
 * @param commandKey    幂等命令键
 * @param orderVersion  移交令版本，全局唯一
 * @param sourceCaseKey 来源案件键
 * @param targetCaseKey 目标案件键
 * @param validFrom     生效时刻（UTC，左闭）
 * @param validUntil    失效时刻（UTC，右开）
 */
public record TransferOrderCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCaseKey,
        @NotBlank @Size(max = 64) String targetCaseKey,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validUntil) {
}
