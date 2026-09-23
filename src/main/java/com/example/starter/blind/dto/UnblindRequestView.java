package com.example.starter.blind.dto;

/**
 * 揭盲申请视图：不含处理代码，任何有权限角色均可查看申请状态。
 *
 * @param requestId       揭盲申请编号
 * @param experimentId    实验编号
 * @param participantId   参与者编号
 * @param reason          揭盲原因
 * @param applicantActor  申请人操作者编号
 * @param reviewerActor   裁决处理人（批准人/拒绝人）；撤销或到期为 null
 * @param status          PENDING / APPROVED / CANCELLED / REJECTED / EXPIRED
 * @param createdAt       申请时间，Unix 毫秒，UTC
 * @param reviewedAt      裁决时间（批准/拒绝），Unix 毫秒，UTC；未裁决为 null
 * @param expiresAt       到期时刻，Unix 毫秒，UTC
 * @param validMinutes    有效时长（分钟）
 * @param terminatedAt    终止时间，Unix 毫秒，UTC；到期固定等于 expiresAt；未终止为 null
 * @param terminateReason 终止原因（拒绝原因/撤销原因）；到期为 null
 * @param terminateActor  终止处理人；到期为 null，不伪造人工处理人
 */
public record UnblindRequestView(
        String requestId,
        String experimentId,
        String participantId,
        String reason,
        String applicantActor,
        String reviewerActor,
        String status,
        long createdAt,
        Long reviewedAt,
        long expiresAt,
        int validMinutes,
        Long terminatedAt,
        String terminateReason,
        String terminateActor
) {
}
