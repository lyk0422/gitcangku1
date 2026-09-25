package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 登记乘务员资质请求。覆盖区段为集合语义，换序视为同参。
 */
public record RegisterQualificationRequest(
        @NotBlank String requestKey,
        @NotBlank String crewId,
        @NotBlank String qualificationCode,
        @NotEmpty List<@NotBlank String> sections,
        @NotNull Instant expiresAtUtc) {
}
