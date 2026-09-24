package com.example.starter.evidence.destruction.dto;

import com.example.starter.evidence.destruction.DestructionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销毁令明细视图：销毁令本体 + 入列证物键（按提交原序）+ 全部审批记录（按提交顺序）。
 *
 * @param destructionKey     销毁令业务键
 * @param submitterId        提交保管人
 * @param legalBasis         法律依据编号
 * @param destructionMethod  销毁方式
 * @param forceIncludeBroken 是否允许封条异常证物入列
 * @param status             销毁令状态
 * @param evidenceKeys       入列证物键，按提交原序
 * @param approvals          审批记录，按提交顺序
 * @param rejectReason       拒绝原因；未拒绝为 null
 * @param rejectedBy         拒绝审批人；未拒绝为 null
 * @param rejectedAt         拒绝时间；未拒绝为 null
 * @param approvedAt         批准时间；未批准为 null
 * @param destroyedAt        执行时间；未执行为 null
 * @param createdAt          创建时间（Asia/Shanghai）
 * @param updatedAt          最近状态变更时间（Asia/Shanghai）
 */
public record DestructionOrderView(
        String destructionKey,
        String submitterId,
        String legalBasis,
        String destructionMethod,
        boolean forceIncludeBroken,
        DestructionStatus status,
        List<String> evidenceKeys,
        List<DestructionApprovalView> approvals,
        String rejectReason,
        String rejectedBy,
        LocalDateTime rejectedAt,
        LocalDateTime approvedAt,
        LocalDateTime destroyedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
