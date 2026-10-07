package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 共同拥有记录确认请求：确认者声明其中一个主体，且请求头 X-Actor-Id 必须等于该主体。
 *
 * @param requestId      幂等请求标识
 * @param subjectKey     确认者声明的主体标识，必须是共同记录两方之一
 * @param expectedVersion 期望的当前提案版本，确认仅对当前版本生效
 */
public record JointRecordConfirmRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull @Min(1) Integer expectedVersion) {
}
