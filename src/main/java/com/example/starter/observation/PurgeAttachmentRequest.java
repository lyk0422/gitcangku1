package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 物理清除附件原始内容请求：清除后原始摘要不可恢复，既有脱敏版本与历史不受影响。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    清除原因（仅记录于请求日志，不回写附件行）
 */
public record PurgeAttachmentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 512) String reason) {
}
