package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 单个弃用窗口登记请求：生效 UTC 时刻、同制品替代版本与可选说明。
 *
 * <p>替代版本必须属于同一制品、已存在且未撤回；生效时刻不得早于当前时钟。
 */
public record DeprecationWindowRequest(
        @NotBlank String name,
        @Positive int version,
        @NotNull Instant effectiveAt,
        @Positive int replacementVersion,
        @Size(max = 512) String reason) {
}
