package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 附件证据视图（需证据权限）：返回原始摘要与全部脱敏版本历史，含已撤销版本。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录唯一标识
 * @param originalSummary 原始内容摘要；已被物理清除时为 null
 * @param contentPurged  原始内容是否已被物理清除
 * @param purgedAtUtc    物理清除时刻（UTC，ISO-8601）；未清除为 null
 * @param redactions     全部脱敏版本历史，按版本号升序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentEvidenceView(
        String attachmentId,
        String observationId,
        String originalSummary,
        boolean contentPurged,
        Instant purgedAtUtc,
        List<RedactionVersionView> redactions) {

    /**
     * 单个脱敏版本的证据详情。
     *
     * @param redactionVersion 脱敏版本号
     * @param ruleVersion      使用的规则版本
     * @param displaySummary   受限展示摘要
     * @param replacementNote  替换说明
     * @param active           是否具备公开展示资格
     * @param revokeReason     撤销原因；未撤销为 null
     * @param createdAtUtc     生成时刻（UTC，ISO-8601）
     * @param revokedAtUtc     撤销时刻（UTC，ISO-8601）；未撤销为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RedactionVersionView(
            int redactionVersion,
            String ruleVersion,
            String displaySummary,
            String replacementNote,
            boolean active,
            String revokeReason,
            Instant createdAtUtc,
            Instant revokedAtUtc) {
    }
}
