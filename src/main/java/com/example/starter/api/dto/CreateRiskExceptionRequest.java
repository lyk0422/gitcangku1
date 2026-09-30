package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 高风险依赖豁免申请的创建请求。
 *
 * <p>豁免精确绑定到 dependencyName + dependencyVersion，不得跨版本适用；
 * 申请创建后为 PENDING，需两名不同审批人针对同一申请版本确认后方可生效。
 *
 * @param dependencyName    被豁免的依赖制品名称
 * @param dependencyVersion 被豁免的依赖精确版本号，正整数
 * @param reason            业务豁免原因，不可为空
 * @param expiresAt         豁免到期的 UTC 时刻；恰好等于该时刻即失效
 */
public record CreateRiskExceptionRequest(
        @NotBlank String dependencyName,
        @Positive int dependencyVersion,
        @NotBlank @Size(max = 512) String reason,
        @NotNull Instant expiresAt) {
}
