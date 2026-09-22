package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 揭盲申请请求：协调员为已分配参与者提出，必须携带原因。
 */
public record UnblindApplyRequest(
        @NotBlank String requestId,
        @NotBlank String participantId,
        @NotBlank String reason) {
}
