package com.example.starter.blind.dto;

/**
 * 揭盲申请视图：不含处理代码，任何有权限角色均可查看申请状态。
 *
 * @param requestId      揭盲申请编号
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param reason         揭盲原因
 * @param applicantActor 申请人操作者编号
 * @param reviewerActor  批准人操作者编号；未批准为 null
 * @param status         PENDING / APPROVED
 * @param createdAt      申请时间，Unix 毫秒，UTC
 * @param reviewedAt     批准时间，Unix 毫秒，UTC；未批准为 null
 * @param unblindType    REGULAR=常规申请-批准；EMERGENCY=紧急通道直接揭盲
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
        String unblindType
) {
}
