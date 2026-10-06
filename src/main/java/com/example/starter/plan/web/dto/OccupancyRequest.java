package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。
 * powerKw 为列车声明取电功率（千瓦，非负整数），缺省按 0 计；
 * 仅当区段已绑定变电所时参与供电预算校验。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @Min(0) Integer powerKw) {

    /**
     * 兼容未声明功率的旧调用：功率缺省为 0。
     */
    public OccupancyRequest(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
        this(trainNo, sectionId, startUtc, endUtc, null);
    }

    /**
     * 声明功率，null 按 0 计。
     */
    public int powerKwOrZero() {
        return powerKw == null ? 0 : powerKw;
    }
}
