package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 弃用窗口登记项：某制品版本的生效 UTC 时刻、同制品替代版本与说明。
 *
 * @param effectiveAt 弃用生效时刻（UTC，左闭：到达即生效），不得早于登记时刻
 * @param replacementVersion 同制品的替代版本号，必须已登记且未撤回
 * @param reason 弃用说明，可为 null（未填写）
 */
public record DeprecationSpec(
        @NotBlank String name,
        @Positive int version,
        @NotNull Instant effectiveAt,
        @Positive int replacementVersion,
        @Size(max = 512) String reason) {
}
