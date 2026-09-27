package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记脱敏规则版本请求。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param ruleVersion 规则版本唯一标识
 * @param description 规则版本说明
 */
public record RegisterRuleRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String ruleVersion,
        @NotBlank @Size(max = 512) String description) {
}
