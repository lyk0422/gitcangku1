package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * 区段/站台占用请求项，区间左闭右开，起止均为 UTC 时刻。
 *
 * <p>普通区段占用仅填前四项；登记编组到发站台占用时须同时提供 platformId 与
 * 编组三要素（长度米、供电制式、无障碍需求），缺一由服务层按 400 拒绝。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        String platformId,
        @Positive Integer consistLengthMeters,
        String powerSystem,
        Boolean accessibleRequired) {

    /** 普通区段占用的便捷构造器（无站台与编组信息）。 */
    public OccupancyRequest(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
        this(trainNo, sectionId, startUtc, endUtc, null, null, null, null);
    }
}
