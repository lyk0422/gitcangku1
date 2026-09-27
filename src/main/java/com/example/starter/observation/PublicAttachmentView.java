package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 公开附件视图：公开查询只返回最新有效脱敏版本的受限展示摘要；无有效脱敏时返回原始摘要。
 *
 * @param attachmentId     附件唯一标识
 * @param observationId    所属观测记录唯一标识
 * @param summary          公开可见摘要：最新有效脱敏版本的展示摘要，或（无有效脱敏时）原始摘要
 * @param redacted         当前公开内容是否为脱敏版本
 * @param redactionVersion 当前公开内容对应的脱敏版本号；未脱敏为 null
 * @param ruleVersion      当前公开内容对应的规则版本；未脱敏为 null
 * @param redactedAtUtc    当前公开脱敏版本的生成时刻（UTC，ISO-8601）；未脱敏为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicAttachmentView(
        String attachmentId,
        String observationId,
        String summary,
        boolean redacted,
        Integer redactionVersion,
        String ruleVersion,
        Instant redactedAtUtc) {
}
