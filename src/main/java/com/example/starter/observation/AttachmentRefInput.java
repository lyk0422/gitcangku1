package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 合并/冲突解决请求中携带的附件证据引用：在生成新观测版本时固化到
 * observation_version_attachment，后续脱敏/撤销不追溯替换。
 *
 * @param attachmentId     被引用的附件标识（必须属于该观测）
 * @param redactionVersion 引用的脱敏版本号；null 表示引用原始附件证据
 */
public record AttachmentRefInput(
        @NotBlank @Size(max = 128) String attachmentId,
        @Positive Integer redactionVersion) {
}
