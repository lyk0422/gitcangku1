package com.example.starter.evidence.dto;

import com.example.starter.evidence.DestructionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销毁申请视图。blockedHolds 为阻断瞬间写入的不可变冻结快照，
 * 冻结之后解除或过期均不改写本视图与库内快照。
 *
 * @param requestKey   销毁申请业务键
 * @param status       申请状态
 * @param requestedBy  提交请求方
 * @param evidenceKeys 规范化证物集合快照
 * @param blockedHolds 命中冻结快照（holdId 稳定排序）；未阻断时为空列表
 * @param createdAt    提交时间（UTC）
 * @param blockedAt    阻断时刻（UTC）；{@code null} 表示未阻断
 * @param completedAt  完成销毁时刻（UTC）；{@code null} 表示未完成
 */
public record DestructionView(
        String requestKey,
        DestructionStatus status,
        String requestedBy,
        List<String> evidenceKeys,
        List<BlockedHoldView> blockedHolds,
        LocalDateTime createdAt,
        LocalDateTime blockedAt,
        LocalDateTime completedAt) {
}
