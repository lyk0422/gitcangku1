package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 保养延期批准请求。批准人必须是与申请人不同的维护角色。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param approver   批准人（维护角色标识），不得与申请人相同
 */
public record ApproveDeferralRequest(
        @NotBlank String requestId,
        @NotBlank String approver) {
}
