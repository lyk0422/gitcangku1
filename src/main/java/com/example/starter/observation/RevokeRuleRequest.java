package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销脱敏规则版本请求：撤销后该规则不可用于新脱敏请求，既有脱敏版本不受影响。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param reason    撤销原因
 */
public record RevokeRuleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 512) String reason) {
}
