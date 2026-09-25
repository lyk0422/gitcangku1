package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * 修改乘务员资质请求：整体替换覆盖区段与到期时刻，expectedVersion 乐观校验。
 */
public record UpdateQualificationRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotEmpty List<@NotBlank String> sections,
        @NotNull Instant expiresAtUtc) {
}
