package com.example.starter.blind.dto;

/**
 * 揭盲申请视图：不含处理代码，任何有权限角色均可查看申请状态与终止信息。
 *
 * @param requestId      揭盲申请编号
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param reason         揭盲原因
 * @param applicantActor 申请人操作者编号
 * @param reviewerActor  批准/拒绝的 REVIEWER 操作者编号；撤销、到期或未处理为 null
 * @param status         PENDING / APPROVED / REJECTED / CANCELLED / EXPIRED
 * @param createdAt      申请时间，Unix 毫秒，UTC
 * @param reviewedAt     批准时间，Unix 毫秒，UTC；未批准为 null
 * @param validMinutes   有效时长（分钟），1~60；历史行按 30 兜底展示
 * @param expiresAt      到期时刻，Unix 毫秒，UTC，固定为创建时间加有效时长，不随后续处理改变
 * @param rejectReason   拒绝原因；仅 REJECTED 非空
 * @param terminatedAt   终态时间，Unix 毫秒，UTC；EXPIRED 固定等于 expiresAt，PENDING 为 null
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
        int validMinutes,
        long expiresAt,
        String rejectReason,
        Long terminatedAt
) {
}
