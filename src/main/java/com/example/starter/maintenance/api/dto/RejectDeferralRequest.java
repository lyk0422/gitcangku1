package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 保养延期拒绝请求。拒绝必须记录理由；拒绝后申请人可换用新 deferKey 重新申请。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param approver   拒绝操作人（维护角色标识）
 * @param reason     拒绝理由（必填）
 */
public record RejectDeferralRequest(
        @NotBlank String requestId,
        @NotBlank String approver,
        @NotBlank String reason) {
}
