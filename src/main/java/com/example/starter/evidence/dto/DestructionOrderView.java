package com.example.starter.evidence.dto;

import com.example.starter.evidence.DestructionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销毁令明细视图：销毁令本体 + 入列证物 + 同意记录。
 *
 * @param destructionKey    销毁令业务键
 * @param submittedBy       提交保管人
 * @param legalBasis        法律依据编号
 * @param destructionMethod 销毁方式
 * @param forceIncludeBroken 是否允许封条异常证物入列
 * @param status            销毁令状态
 * @param approvals         已同意的审批记录（按同意顺序）
 * @param items             入列证物（按入列顺序）
 * @param rejectReason      拒绝原因；NULL 表示未被拒绝
 * @param createdAt         创建时间（Asia/Shanghai）
 * @param decidedAt        双人审批完成或拒绝时间；NULL 表示仍在 PENDING
 * @param executedAt       执行销毁时间；NULL 表示未执行
 */
public record DestructionOrderView(
        String destructionKey,
        String submittedBy,
        String legalBasis,
        String destructionMethod,
        boolean forceIncludeBroken,
        DestructionStatus status,
        List<DestructionApprovalView> approvals,
        List<DestructionItemView> items,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        LocalDateTime executedAt) {
}
