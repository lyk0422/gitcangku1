package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁申请实体，对应 destruction_request 表。只追加、不可变。
 * DENIED 表示当时存在有效冻结，事后冻结到期或解除不改写该结论。
 *
 * @param id             主键
 * @param evidenceKey    关联证物业务键
 * @param requestId      销毁申请业务键（幂等键），全局唯一
 * @param requestedBy    提交申请的当前保管人
 * @param reason         销毁原因，非空
 * @param status         申请结论：ALLOWED 已销毁 / DENIED 被有效冻结拒绝
 * @param blockingHoldId 阻断销毁的冻结主键；无阻断时为 null
 * @param requestedAt    申请提交时间（Asia/Shanghai）
 */
public record DestructionRequest(
        Long id,
        String evidenceKey,
        String requestId,
        String requestedBy,
        String reason,
        DestructionStatus status,
        Long blockingHoldId,
        LocalDateTime requestedAt) {
}
