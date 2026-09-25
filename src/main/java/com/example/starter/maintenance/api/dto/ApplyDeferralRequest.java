package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 保养延期申请请求。仅允许处于 DUE（本轮运行分钟达到保养周期）且保养未完成的设备申请；
 * 同一设备同时只允许一条待审批延期。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param deferKey  延期申请标识，设备内唯一
 * @param minutes   申请延期分钟数，1～10080，且不超过保养周期的 25%
 * @param reason    申请原因
 * @param applicant 申请人标识
 */
public record ApplyDeferralRequest(
        @NotBlank String requestId,
        @NotBlank String deferKey,
        @NotNull @Min(1) @Max(10080) Long minutes,
        @NotBlank String reason,
        @NotBlank String applicant) {
}
