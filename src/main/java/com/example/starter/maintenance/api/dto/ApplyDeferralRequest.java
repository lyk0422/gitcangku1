package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 保养延期申请请求。仅处于 DUE 且尚未完成保养的设备可申请；同一设备同时只允许一条待审批延期。
 *
 * @param requestId     全局唯一请求标识（幂等键）
 * @param deferKey      延期业务标识，设备内唯一（含历史记录，不可复用）
 * @param deferMinutes  申请延期分钟数，1～10080；单次不得超过保养周期 25%
 * @param reason        申请原因
 * @param applicant     申请人（维护角色标识）
 */
public record ApplyDeferralRequest(
        @NotBlank String requestId,
        @NotBlank String deferKey,
        @NotNull @Min(1) @Max(10080) Long deferMinutes,
        @NotBlank String reason,
        @NotBlank String applicant) {
}
