package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 附件公开视图：只返回最新有效脱敏版本的受限展示摘要；无有效脱敏版本时返回原始摘要。
 *
 * @param attachmentId     附件唯一标识
 * @param observationId    所属观测记录标识
 * @param displaySummary   公开展示摘要（最新有效脱敏版本的受限摘要，无有效版本时为原始摘要）
 * @param source           摘要来源：REDACTION / ORIGINAL
 * @param redactionVersion 当前展示的脱敏版本号；source 为 ORIGINAL 时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentPublicView(
        String attachmentId,
        String observationId,
        String displaySummary,
        String source,
        Integer redactionVersion) {

    public static final String SOURCE_REDACTION = "REDACTION";
    public static final String SOURCE_ORIGINAL = "ORIGINAL";
}
