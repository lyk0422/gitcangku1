package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销脱敏规则版本请求。撤销不可逆；撤销后该规则不得再用于新脱敏（422），既有脱敏版本不受影响。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param role      请求角色：撤销规则需要 REDACTOR 或 ADMIN
 * @param operator  执行撤销的操作者标识
 * @param reason    撤销理由（必填，随规则行保留）
 */
public record RevokeRuleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 16) String role,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 1024) String reason) {
}
