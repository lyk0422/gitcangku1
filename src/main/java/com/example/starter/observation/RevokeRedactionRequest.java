package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销脱敏版本请求：仅撤销公开展示资格，保留脱敏版本、理由和撤销时刻。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param role      操作角色，仅 REDACTOR 允许撤销
 * @param reason    撤销理由
 */
public record RevokeRedactionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 32) String role,
        @NotBlank @Size(max = 1024) String reason) {
}
