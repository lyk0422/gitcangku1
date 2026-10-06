package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。
 * speedV 可空：填非负速度（米每秒，最多 3 位小数）时参与制动包络检查；
 * 不填表示无速度占用，不适用包络检查，不能补零冒充。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @PositiveOrZero @Digits(integer = 15, fraction = 3) BigDecimal speedV) {
}
