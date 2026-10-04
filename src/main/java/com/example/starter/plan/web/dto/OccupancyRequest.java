package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。routeId 可空，空表示不参与进路互斥检测。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        String routeId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc) {

    /**
     * 无进路编号的占用请求便捷构造。
     */
    public OccupancyRequest(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
        this(trainNo, sectionId, null, startUtc, endUtc);
    }
}