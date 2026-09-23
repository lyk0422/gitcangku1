package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重新封存确认/撤销命令请求：携带幂等命令键与目标申请的 resealKey。
 *
 * @param commandKey 幂等命令键
 * @param resealKey  目标重新封存申请业务键
 */
public record ResealDecisionRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String resealKey) {
}
