package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。
 * fromStation/toStation 可空；登记站点后可用于接续关系的站点连续性校验。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        String fromStation,
        String toStation,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc) {

    /**
     * 不登记站点的占用请求（等价于 fromStation/toStation 均为 null）。
     */
    public OccupancyRequest(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
        this(trainNo, sectionId, null, null, startUtc, endUtc);
    }
}
