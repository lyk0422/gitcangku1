package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。
 * powerKw 为列车声明取电功率（非负整数千瓦），缺省按 0 处理。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @Min(0) Integer powerKw) {

    /**
     * 兼容旧四参构造：未声明功率时按 0 千瓦处理。
     */
    public OccupancyRequest(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
        this(trainNo, sectionId, startUtc, endUtc, 0);
    }

    /**
     * 声明功率（千瓦），null 按 0 处理。
     */
    public int powerKwOrZero() {
        return powerKw == null ? 0 : powerKw;
    }
}
