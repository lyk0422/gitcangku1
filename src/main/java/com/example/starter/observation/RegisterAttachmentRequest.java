package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记观测附件请求：附件携带原始内容摘要并归属于一条观测记录。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param observationId  所属观测记录唯一标识
 * @param attachmentId   附件唯一标识
 * @param contentSummary 附件原始内容摘要；登记后不可修改，只能被物理清除
 */
public record RegisterAttachmentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 128) String attachmentId,
        @NotBlank @Size(max = 2048) String contentSummary) {
}
