package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 保留冻结实体，对应 retention_hold 表。记录只追加，解除仅写一次解除链字段。
 *
 * @param id                 主键
 * @param requestId          冻结请求幂等键，全局唯一
 * @param requestHash        冻结请求参数指纹，用于识别同键换对象或到期时间
 * @param scope              冻结层级：CASE 案件级 / EVIDENCE 证物级
 * @param caseKey            案件键（案件级即冻结对象，证物级为证物所属案件）
 * @param evidenceKey        证物级冻结的证物键；案件级为 null
 * @param expiresUtc         UTC 到期时刻，有效期右边界（不含）
 * @param reason             冻结创建原因
 * @param createdBy          冻结创建保管人
 * @param createdUtc         冻结生效时刻（UTC），有效期左边界（含）
 * @param status             冻结状态：ACTIVE / RELEASED
 * @param releaseRequestId   解除请求幂等键；null 未解除
 * @param releaseRequestHash 解除请求参数指纹，用于识别同键换冻结或原因；null 未解除
 * @param releasedBy         解除保管人（必须不同于 createdBy）；null 未解除
 * @param releaseReason      解除原因；null 未解除
 * @param releasedUtc        解除时刻（UTC）；null 未解除
 * @param createdAt          记录创建时间（Asia/Shanghai）
 */
public record RetentionHold(
        Long id,
        String requestId,
        String requestHash,
        HoldScope scope,
        String caseKey,
        String evidenceKey,
        LocalDateTime expiresUtc,
        String reason,
        String createdBy,
        LocalDateTime createdUtc,
        HoldStatus status,
        String releaseRequestId,
        String releaseRequestHash,
        String releasedBy,
        String releaseReason,
        LocalDateTime releasedUtc,
        LocalDateTime createdAt) {
}
