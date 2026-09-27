package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 物理清除附件内容请求：仅清除原始内容摘要并置清除标记，附件元数据与既有脱敏版本保留。
 * 清除不可逆，撤销脱敏也不能恢复已清除内容（ADMIN）。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    清除理由
 */
public record PurgeAttachmentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String reason) {
}
