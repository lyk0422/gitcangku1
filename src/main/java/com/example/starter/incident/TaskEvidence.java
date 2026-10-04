package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务验收证据实体，对应 incident_task_evidences 表。
 * (taskId, evidenceKey) 唯一，同键同内容幂等，同键不同内容冲突；VOIDED 终态不可复用键。
 * confirmedBy/confirmedAt 仅 CONFIRMED 有值，voidedBy/voidedAt 仅 VOIDED 有值，
 * frozenAt 在任务完成时统一写入，此后证据内容与确认人冻结不可改。时间均为 UTC。
 */
public record TaskEvidence(
        long id,
        long taskId,
        String evidenceKey,
        String category,
        String note,
        EvidenceStatus status,
        String uploadedBy,
        Instant uploadedAt,
        String confirmedBy,
        Instant confirmedAt,
        String voidedBy,
        Instant voidedAt,
        Instant frozenAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条证据的业务内容是否一致（用于 evidenceKey 幂等比对）。
     */
    public boolean sameContent(String category, String note, String uploadedBy) {
        return this.category.equals(category) && this.note.equals(note)
                && this.uploadedBy.equals(uploadedBy);
    }
}
