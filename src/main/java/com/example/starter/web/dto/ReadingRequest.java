package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 计量读数提交请求；基线与实际作为同一版本一起提交，二者时段必须一致（UTC 左闭右开）。
 * 首版为计量确认，再次提交即读数更正，此时 reason 必填。
 *
 * @param userId            用户 ID
 * @param baselineKwh       基线电量（kWh，不为负）
 * @param actualKwh         实际电量（kWh，不为负）
 * @param baselinePeriodStart 基线读数时段起点（UTC，含）
 * @param baselinePeriodEnd   基线读数时段终点（UTC，不含）
 * @param actualPeriodStart   实际读数时段起点（UTC，含）
 * @param actualPeriodEnd     实际读数时段终点（UTC，不含）
 * @param reason            更正缘由；首版留空，更正时必填
 */
public record ReadingRequest(
        @NotBlank String userId,
        @NotNull BigDecimal baselineKwh,
        @NotNull BigDecimal actualKwh,
        @NotNull Instant baselinePeriodStart,
        @NotNull Instant baselinePeriodEnd,
        @NotNull Instant actualPeriodStart,
        @NotNull Instant actualPeriodEnd,
        String reason
) {
}
