package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 漂移修正锚点请求：维护员对一条现存读数给出经校准的真实累计工时。
 *
 * @param readingId        锚点读数标识，设备内须已存在
 * @param expectedVersion  锚点读数期望修订号；激活重读时与当前修订号不一致整单拒绝（409）
 * @param calibratedHours  经校准的真实累计工时（小时），精确到 0.001 小时，非负
 */
public record DriftAnchorRequest(
        @NotBlank String readingId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull @PositiveOrZero BigDecimal calibratedHours) {
}
