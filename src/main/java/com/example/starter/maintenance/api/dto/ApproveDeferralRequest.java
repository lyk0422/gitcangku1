package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批准延期请求。审批人必须与申请人不同（双人审批）。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param approver  审批人标识，不得与申请人相同
 */
public record ApproveDeferralRequest(
        @NotBlank String requestId,
        @NotBlank String approver) {
}
