package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 附件脱敏响应：一次脱敏请求的全部附件项结果，顺序与请求一致。
 *
 * @param redactionId  全局唯一脱敏请求标识
 * @param observationId 所属观测记录唯一标识
 * @param items        各附件项的脱敏结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionResponse(
        String redactionId,
        String observationId,
        List<Item> items) {

    /**
     * 单个附件项的脱敏结果。
     *
     * @param attachmentId     附件唯一标识
     * @param redactionVersion 该附件内递增的脱敏版本号
     * @param ruleVersion      使用的脱敏规则版本
     * @param displaySummary   受限展示摘要（公开查询返回的替换文本）
     * @param createdAtUtc     脱敏版本生成时刻（UTC，ISO-8601）
     */
    public record Item(
            String attachmentId,
            int redactionVersion,
            String ruleVersion,
            String displaySummary,
            Instant createdAtUtc) {
    }
}
