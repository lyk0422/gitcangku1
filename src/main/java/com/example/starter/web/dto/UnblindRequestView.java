package com.example.starter.web.dto;

/**
 * 揭盲申请视图。
 *
 * @param unblindRequestId 揭盲申请主键（服务端生成，批准/结果接口使用）
 * @param experimentId     实验编号
 * @param participantId    参与者编号
 * @param reason           申请原因
 * @param applicantId      申请人（协调员）编号
 * @param approverId       批准人编号，未批准为空
 * @param status           申请状态（PENDING / APPROVED）
 */
public record UnblindRequestView(
        long unblindRequestId,
        String experimentId,
        String participantId,
        String reason,
        String applicantId,
        String approverId,
        String status
) {
}
