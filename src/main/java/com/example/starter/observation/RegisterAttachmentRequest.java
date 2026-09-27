package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记观测附件元数据请求（REDACTOR/EVIDENCE）。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param attachmentId   附件唯一标识（同观测内唯一，重复登记返回 409）
 * @param contentSummary 附件原始内容摘要；可为空表示登记时原文即不可得
 * @param mediaType      附件媒体类型
 */
public record RegisterAttachmentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String attachmentId,
        @Size(max = 2048) String contentSummary,
        @NotBlank @Size(max = 128) String mediaType) {
}
