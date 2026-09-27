package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 观测版本固化的附件证据引用明细（证据权限）。
 * 引用在观测版本生成时固化，后续脱敏/撤销不追溯替换。
 *
 * @param observationId    观测记录标识
 * @param version          观测版本号
 * @param attachmentId     被引用的附件标识
 * @param redactionVersion 固化的脱敏版本号；null 表示引用原始附件证据
 * @param summary          固化引用对应的摘要内容（原始或脱敏后，按 redactionVersion 解析）
 * @param createdAtUtc     引用固化时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionAttachmentRefResponse(
        String observationId,
        int version,
        String attachmentId,
        Integer redactionVersion,
        String summary,
        Instant createdAtUtc) {
}
