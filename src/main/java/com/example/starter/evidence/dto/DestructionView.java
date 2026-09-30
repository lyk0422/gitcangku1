package com.example.starter.evidence.dto;

import com.example.starter.evidence.DestructionStatus;

import java.time.LocalDateTime;

/**
 * 销毁申请视图。历史裁决不可改写：冻结到期或解除后，过去 REJECTED 记录仍保持 REJECTED。
 *
 * @param requestId      销毁申请幂等键
 * @param evidenceKey    证物键
 * @param caseKey        申请时所属案件键
 * @param reason         销毁原因
 * @param requestedBy    申请保管人
 * @param status         裁决结果：APPROVED / REJECTED
 * @param blockingHoldId 阻断冻结 id；null 表示未被冻结阻断
 * @param requestedUtc   裁决时刻（UTC）
 */
public record DestructionView(
        String requestId,
        String evidenceKey,
        String caseKey,
        String reason,
        String requestedBy,
        DestructionStatus status,
        Long blockingHoldId,
        LocalDateTime requestedUtc) {
}
