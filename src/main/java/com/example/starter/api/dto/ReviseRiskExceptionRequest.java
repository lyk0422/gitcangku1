package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 豁免申请修订请求：仅 PENDING 状态可修订；修订后申请版本加一，既有审批全部作废。
 *
 * @param reason    修订后的业务豁免原因
 * @param expiresAt 修订后的到期 UTC 时刻，必须晚于当前时刻
 */
public record ReviseRiskExceptionRequest(
        @NotBlank @Size(max = 512) String reason,
        @NotNull Instant expiresAt) {
}
