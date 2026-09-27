package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 物理清除附件内容请求（合规处置）。清除后原始内容摘要不可恢复；
 * 既有脱敏版本与历史证据引用不受影响。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param role      请求角色：物理清除需要 ADMIN
 * @param operator  执行清除的操作者标识
 * @param reason    清除理由（必填，记入诊断）
 */
public record PurgeAttachmentRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 1024) String reason) {
}
