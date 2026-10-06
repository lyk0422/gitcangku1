package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。
 * speed 为占用速度 v（米每秒，非负、最多3位小数）；缺省表示原无速度占用，
 * 不适用应急制动包络判定，不能以 0 冒充。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @DecimalMin("0") @Digits(integer = 6, fraction = 3) BigDecimal speed) {

    /**
     * 原无速度占用构造（保持原接口）。
     */
    public OccupancyRequest(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
        this(trainNo, sectionId, startUtc, endUtc, null);
    }
}
