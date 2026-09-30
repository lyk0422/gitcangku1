package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 保留冻结实体，对应 retention_hold 表。
 * 案件级冻结 level=CASE、scopeKey=caseKey、evidenceKey=null；
 * 证物级冻结 level=EVIDENCE、scopeKey=evidenceKey、caseKey 为证物所属案件。
 * 记录只追加，解除仅回填 release* 字段；有效期为 UTC 窗口 [createdAt 起, expiresAt)，
 * 当前 UTC 时刻不早于 expiresAt 即自然失效，行不被改写。
 *
 * @param id               主键
 * @param holdRequestId    冻结申请业务键（幂等键），全局唯一
 * @param level            冻结级别（CASE/EVIDENCE）
 * @param caseKey          案件键（案件级即被冻结案件）
 * @param scopeKey         冻结对象键（案件键或证物业务键）
 * @param evidenceKey      证物级冻结关联证物；案件级为 null
 * @param expiresAt        UTC 到期时刻，窗口右端（不含）
 * @param reason           创建原因，非空
 * @param createdBy        创建保管人；解除人必须不同
 * @param status           行状态：ACTIVE 未解除 / RELEASED 已解除
 * @param createdAt        创建时间（Asia/Shanghai）
 * @param releasedBy       解除保管人；null 表示未解除
 * @param releaseReason    解除原因；null 表示未解除
 * @param releaseRequestId 解除申请业务键（幂等键）；null 表示未解除
 * @param releasedAt       UTC 解除时刻；null 表示未解除
 */
public record RetentionHold(
        Long id,
        String holdRequestId,
        HoldLevel level,
        String caseKey,
        String scopeKey,
        String evidenceKey,
        LocalDateTime expiresAt,
        String reason,
        String createdBy,
        HoldStatus status,
        LocalDateTime createdAt,
        String releasedBy,
        String releaseReason,
        String releaseRequestId,
        LocalDateTime releasedAt) {

    /**
     * 按给定 UTC 时刻判断冻结是否仍阻断销毁：未解除且 now &lt; expiresAt（窗口左闭右开）。
     *
     * @param nowUtc 当前 UTC 时刻（按 UTC 解释的 LocalDateTime）
     * @return true 表示冻结有效、阻断销毁
     */
    public boolean effectiveAt(LocalDateTime nowUtc) {
        return status == HoldStatus.ACTIVE && nowUtc.isBefore(expiresAt);
    }
}
