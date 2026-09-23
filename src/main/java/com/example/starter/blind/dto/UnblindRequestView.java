package com.example.starter.blind.dto;

/**
 * 揭盲申请视图：不含处理代码，任何有权限角色均可查看申请状态。
 * status 为按查询时钟裁决后的有效状态：PENDING 到达 expiresAt 时展示为 EXPIRED（不写库）。
 *
 * @param requestId       揭盲申请编号
 * @param experimentId    实验编号
 * @param participantId   参与者编号
 * @param reason          揭盲原因
 * @param applicantActor  申请人操作者编号
 * @param reviewerActor   批准人操作者编号；未批准为 null
 * @param status          PENDING / APPROVED / CANCELLED / REJECTED / EXPIRED
 * @param createdAt       申请时间，Unix 毫秒，UTC
 * @param reviewedAt      批准时间，Unix 毫秒，UTC；未批准为 null
 * @param validMinutes    有效时长，单位分钟，1~60
 * @param expiresAt       过期时刻，Unix 毫秒，UTC；历史申请按创建时间加 30 分钟计算
 * @param terminateReason 终止原因：REJECTED 时为拒绝原因；其余状态为 null
 * @param terminatedActor 终态处理人：CANCELLED=申请人，REJECTED=拒绝的 REVIEWER；EXPIRED 恒为 null
 * @param terminatedAt    终态时间，Unix 毫秒，UTC；EXPIRED 固定等于 expiresAt
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
        String terminateReason,
        String terminatedActor,
        Long terminatedAt
) {
}
