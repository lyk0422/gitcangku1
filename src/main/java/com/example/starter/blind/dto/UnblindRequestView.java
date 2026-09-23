package com.example.starter.blind.dto;

/**
 * 揭盲申请视图：不含处理代码，任何有权限角色均可查看申请状态。
 * 到期（EXPIRED）在普通查询中按查询时钟展示，不落库；expiresAt 固定为到期时刻。
 *
 * @param requestId      揭盲申请编号
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param reason         揭盲原因
 * @param applicantActor 申请人操作者编号
 * @param reviewerActor  批准人操作者编号；未批准为 null
 * @param status         PENDING / APPROVED / CANCELLED / REJECTED / EXPIRED
 * @param createdAt      申请时间，Unix 毫秒，UTC
 * @param reviewedAt     批准时间，Unix 毫秒，UTC；未批准为 null
 * @param expiresAt      到期时刻，Unix 毫秒，UTC；已批准结果不追溯设限
 * @param handlerActor   终态处理人：批准/拒绝为对应 REVIEWER，撤销为申请人，到期为 null
 * @param terminatedAt   终态形成时间，Unix 毫秒，UTC；到期固定为 expiresAt；未终止为 null
 * @param terminalReason 终态原因（拒绝原因/撤销说明/过期说明）；PENDING/APPROVED 为 null
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
        String handlerActor,
        Long terminatedAt,
        String terminalReason
) {
}
