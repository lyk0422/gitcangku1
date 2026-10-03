package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 气象最低标准窗口（UTC 时段，左闭右开）。
 * 负值标准返回 422；同一区域内时段重叠返回 422。
 *
 * @param windowStartUtc 时段起点（epoch 毫秒，UTC，左闭）
 * @param windowEndUtc   时段终点（epoch 毫秒，UTC，右开，必须大于起点）
 * @param visibilityMinM 能见度最低标准（米，必须非负）
 * @param cloudBaseMinM  云底最低标准（米，必须非负）
 */
public record MinimaWindowDto(
        @NotNull Long windowStartUtc,
        @NotNull Long windowEndUtc,
        @NotNull Integer visibilityMinM,
        @NotNull Integer cloudBaseMinM) {
}