package com.example.starter.db;

import java.time.Instant;

/**
 * unblind_request 表行：揭盲申请及审批结果。
 *
 * @param id            申请主键
 * @param experimentId  实验编号
 * @param allocationId  关联分配主键
 * @param participantId 参与者编号
 * @param applicantId   申请人（协调员）编号
 * @param reason        申请原因
 * @param status        PENDING/APPROVED
 * @param approverId    批准人（非申请人的 REVIEWER）编号，未批准为空
 * @param createdAt     申请时间（UTC）
 * @param approvedAt    批准时间（UTC），未批准为空
 */
public record UnblindRequestRow(
        long id,
        String experimentId,
        long allocationId,
        String participantId,
        String applicantId,
        String reason,
        String status,
        String approverId,
        Instant createdAt,
        Instant approvedAt
) {
}
