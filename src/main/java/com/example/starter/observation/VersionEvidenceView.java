package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 观测版本证据视图（需证据权限）：返回该观测版本生成时固化的附件证据引用，
 * 后续脱敏与撤销不追溯替换；每条引用可解析出当时的原始摘要与脱敏摘要。
 *
 * @param observationId      观测记录唯一标识
 * @param observationVersion 观测版本号
 * @param items              该版本固化的附件证据引用，按附件标识排序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionEvidenceView(
        String observationId,
        int observationVersion,
        List<Item> items) {

    /**
     * 单条固化的证据引用。
     *
     * @param attachmentId     附件唯一标识
     * @param redactionVersion 固化时引用的脱敏版本号；当时无有效脱敏为 null（快照公开原始摘要）
     * @param originalSummary  附件原始摘要；已被物理清除时为 null
     * @param displaySummary   引用脱敏版本的受限展示摘要；无引用脱敏版本时为 null
     * @param ruleVersion      引用脱敏版本的规则版本；无引用脱敏版本时为 null
     * @param frozenAtUtc      证据固化时刻（UTC，ISO-8601）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String attachmentId,
            Integer redactionVersion,
            String originalSummary,
            String displaySummary,
            String ruleVersion,
            Instant frozenAtUtc) {
    }
}
