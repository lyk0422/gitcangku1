package com.example.starter.plan.web.dto;

import com.example.starter.plan.model.Direction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 区段占用请求项，区间左闭右开，起止均为 UTC 时刻。
 * direction 可空：未配置单线换向的区段可不指定；已配置区段发布时必须携带。
 */
public record OccupancyRequest(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        Direction direction) {
}
