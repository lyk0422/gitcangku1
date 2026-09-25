package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 拒绝延期请求。拒绝必须记录理由；拒绝后申请人可重新申请（新的 deferKey），仍受累计上限约束。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param approver  审批人标识
 * @param reason    拒绝理由
 */
public record RejectDeferralRequest(
        @NotBlank String requestId,
        @NotBlank String approver,
        @NotBlank String reason) {
}
