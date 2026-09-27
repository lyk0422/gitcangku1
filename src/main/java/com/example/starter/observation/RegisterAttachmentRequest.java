package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记观测附件元数据请求。附件归属与原始内容摘要一经登记不可修改。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param role           请求角色：登记附件需要 REDACTOR 或 ADMIN
 * @param attachmentId   附件唯一标识
 * @param filename       附件文件名；可为 null
 * @param mediaType      附件媒体类型；可为 null
 * @param contentSummary 附件原始内容摘要（脱敏的源内容）
 */
public record RegisterAttachmentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 128) String attachmentId,
        @Size(max = 256) String filename,
        @Size(max = 128) String mediaType,
        @NotBlank @Size(max = 4000) String contentSummary) {
}
