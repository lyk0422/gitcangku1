package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 附件原始内容物理清除请求：清除后原始摘要不可再读，且撤销脱敏也无法恢复内容。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    清除理由
 * @param operator  执行清除的操作者标识
 */
public record ClearAttachmentContentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String reason,
        @NotBlank @Size(max = 128) String operator) {
}
