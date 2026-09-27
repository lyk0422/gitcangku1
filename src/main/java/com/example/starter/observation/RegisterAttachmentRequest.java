package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记观测附件请求：附件属于且仅属于一条观测记录，原始内容摘要登记后不可修改。
 * contentSummary 长度上限由服务端校验，超长返回 422 并携带实际值与要求值。
 *
 * @param requestId      全局唯一请求标识（幂等去重键）
 * @param attachmentId   附件唯一标识
 * @param contentSummary 原始内容摘要
 */
public record RegisterAttachmentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String attachmentId,
        @NotBlank String contentSummary) {
}
