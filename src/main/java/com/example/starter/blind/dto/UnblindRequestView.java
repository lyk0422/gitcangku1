package com.example.starter.blind.dto;

/**
 * 揭盲申请视图：不含处理代码，任何有权限角色均可查看申请状态。
 *
 * @param requestId      揭盲申请编号
 * @param experimentId   实验编号
 * @param participantId  参与者编号
 * @param reason         揭盲原因
 * @param applicantActor 申请人操作者编号；紧急揭盲时为提交的 REVIEWER
 * @param reviewerActor  批准人操作者编号；未批准为 null；紧急揭盲时等于提交的 REVIEWER
 * @param status         PENDING / APPROVED
 * @param createdAt      申请时间，Unix 毫秒，UTC
 * @param reviewedAt     批准时间，Unix 毫秒，UTC；未批准为 null
 * @param requestType    REGULAR=常规申请批准通道；EMERGENCY=SEVERE 不良事件紧急揭盲
 * @param eventKey       紧急揭盲依据的不良事件报告业务键；常规申请为 null
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
        String requestType,
        String eventKey
) {
}
