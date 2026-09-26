package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 修订气象限速令请求：对路径中的 restrictionKey 追加新版本并原子撤销上一生效版本。
 * requestKey 为幂等键；指纹包含新限速参数与操作者。
 */
public record ReviseRestrictionRequest(
        @NotBlank String requestKey,
        @NotNull String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer maxSpeedKmh,
        @NotBlank String operator) {
}
