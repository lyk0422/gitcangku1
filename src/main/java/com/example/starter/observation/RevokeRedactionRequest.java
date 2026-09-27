package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销脱敏公开展示资格请求。撤销只翻转公开展示资格，保留脱敏版本、理由与撤销时刻。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param role      请求角色：撤销脱敏需要 REDACTOR 或 ADMIN
 * @param operator  执行撤销的操作者标识
 * @param reason    撤销理由（必填，随版本行保留）
 */
public record RevokeRedactionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 1024) String reason) {
}
