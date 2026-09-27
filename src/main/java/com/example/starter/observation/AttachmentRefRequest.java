package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 合并/冲突解决请求中的单条附件证据引用：固化附件归属的原始摘要或指定脱敏版本。
 *
 * @param attachmentId 被引用的附件标识，必须属于当前观测
 * @param kind         引用类型：ORIGINAL 原始摘要 / REDACTED 指定脱敏版本
 * @param redactionId  指定脱敏版本标识；kind=REDACTED 时必填，ORIGINAL 时必须为空
 */
public record AttachmentRefRequest(
        @NotBlank @Size(max = 64) String attachmentId,
        @NotNull AttachmentRefKind kind,
        @Size(max = 256) String redactionId) {
}
