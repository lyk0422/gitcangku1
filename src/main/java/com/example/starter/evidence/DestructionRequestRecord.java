package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁申请实体，对应 destruction_request 表。记录只追加、不可改写。
 *
 * @param id             主键
 * @param requestId      销毁申请幂等键，全局唯一
 * @param requestHash    销毁申请参数指纹，用于识别同键换证物或原因
 * @param evidenceKey    被申请销毁的证物键
 * @param caseKey        申请时证物所属案件键
 * @param reason         销毁原因
 * @param requestedBy    申请保管人
 * @param status         裁决结果：APPROVED / REJECTED
 * @param blockingHoldId 阻断销毁的冻结 id；null 表示未被冻结阻断
 * @param requestedUtc   裁决时刻（UTC）
 * @param createdAt      记录创建时间（Asia/Shanghai）
 */
public record DestructionRequestRecord(
        Long id,
        String requestId,
        String requestHash,
        String evidenceKey,
        String caseKey,
        String reason,
        String requestedBy,
        DestructionStatus status,
        Long blockingHoldId,
        LocalDateTime requestedUtc,
        LocalDateTime createdAt) {
}
