package com.example.starter.evidence.dto;

import com.example.starter.evidence.DestructionStatus;

import java.time.LocalDateTime;

/**
 * 销毁申请视图。结论只追加：事后冻结到期或解除不改写 DENIED 记录。
 *
 * @param evidenceKey    关联证物业务键
 * @param requestId      销毁申请业务键（幂等键）
 * @param requestedBy    提交申请的当前保管人
 * @param reason         销毁原因
 * @param status         申请结论：ALLOWED 已销毁 / DENIED 被有效冻结拒绝
 * @param blockingHoldId 阻断销毁的冻结主键；无阻断时为 null
 * @param requestedAt    申请提交时间（Asia/Shanghai）
 */
public record DestructionView(
        String evidenceKey,
        String requestId,
        String requestedBy,
        String reason,
        DestructionStatus status,
        Long blockingHoldId,
        LocalDateTime requestedAt) {
}
