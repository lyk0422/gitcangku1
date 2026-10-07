package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 共同研究记录确认请求：确认者声明其中一个主体，请求头 X-Actor-Id 必须等于该主体。
 *
 * @param requestId 幂等请求标识
 * @param subject   声明确认的主体标识，必须是记录两方之一
 * @param version   被确认的提案版本，从 1 开始
 */
public record JointConfirmRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subject,
        @NotNull @Min(1) Integer version) {
}
