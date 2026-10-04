package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务验收证据实体，对应 task_evidence 表。
 * (taskId, evidenceNo) 唯一，同号同内容幂等返回，同号不同内容冲突；
 * confirmedBy/confirmedAt 仅 CONFIRMED 有值，voidedBy/voidedAt 仅 VOIDED 有值；
 * frozenAt 在任务完成时写入，此后禁止作废或覆盖。时间均为 UTC。
 */
public record TaskEvidence(
        long id,
        long taskId,
        String evidenceNo,
        String category,
        String description,
        EvidenceStatus status,
        String uploadedBy,
        Instant uploadedAt,
        String confirmedBy,
        Instant confirmedAt,
        String voidedBy,
        Instant voidedAt,
        Instant frozenAt,
        Instant createdAt) {

    /**
     * 判断两条证据的业务内容是否一致（用于 evidenceNo 幂等比对）。
     */
    public boolean sameContent(String category, String description) {
        return this.category.equals(category) && this.description.equals(description);
    }
}