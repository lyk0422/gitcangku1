package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养延期记录视图。批准后原阈值/新阈值快照不可变。
 *
 * @param deferralId                 延期记录主键
 * @param equipmentId                所属设备标识
 * @param deferKey                   延期业务标识
 * @param cycleNo                    所属保养周期序号（从 0 计）
 * @param deferMinutes               申请延期分钟数
 * @param reason                     申请原因
 * @param applicant                  申请人
 * @param approver                   审批人；未审批为 null
 * @param rejectReason               拒绝理由；仅拒绝时有值
 * @param status                     PENDING / APPROVED / REJECTED / EXPIRED
 * @param appliedCumulativeMinutes   申请时设备最新累计工时快照（分钟）
 * @param originalThresholdMinutes   批准时本周期原阈值（分钟）；未批准为 null
 * @param newThresholdMinutes        批准时本周期新阈值（分钟）；未批准为 null
 * @param createdAt                  申请时刻（UTC）
 * @param decidedAt                  审批或失效时刻（UTC）；未审批为 null
 */
public record DeferralResponse(
        long deferralId,
        String equipmentId,
        String deferKey,
        int cycleNo,
        long deferMinutes,
        String reason,
        String applicant,
        String approver,
        String rejectReason,
        String status,
        long appliedCumulativeMinutes,
        Long originalThresholdMinutes,
        Long newThresholdMinutes,
        Instant createdAt,
        Instant decidedAt) {
}
