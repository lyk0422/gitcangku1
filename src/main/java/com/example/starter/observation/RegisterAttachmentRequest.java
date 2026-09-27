package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 附件登记请求：由现场工程师把带内容摘要的附件元数据挂到指定观测记录下。
 *
 * @param attachmentId    附件唯一标识
 * @param fileName        附件文件名
 * @param contentType     附件内容类型（MIME）
 * @param sizeBytes       附件内容大小（字节，非负）
 * @param originalSummary 原始内容摘要原文
 */
public record RegisterAttachmentRequest(
        @NotBlank @Size(max = 64) String attachmentId,
        @NotBlank @Size(max = 512) String fileName,
        @NotBlank @Size(max = 128) String contentType,
        @NotNull @PositiveOrZero Long sizeBytes,
        @NotBlank @Size(max = 2048) String originalSummary) {
}
