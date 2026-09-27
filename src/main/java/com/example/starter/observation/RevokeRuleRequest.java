package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销脱敏规则版本请求：撤销后该规则不能再用于新的脱敏（既有脱敏版本保留且仍有效）。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    撤销理由
 */
public record RevokeRuleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 1024) String reason) {
}
