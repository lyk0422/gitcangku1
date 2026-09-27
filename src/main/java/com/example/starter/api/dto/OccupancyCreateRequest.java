package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建空域占用申请（含被让渡后的显式重新申请）。时段与高度范围均为左闭右开；
 * 时间统一使用 UTC epoch 毫秒。占用航线必须存在当前有效的 CLEAR 审查结论。
 *
 * @param occupancyId   占用唯一标识
 * @param routeId       航线唯一标识
 * @param routeVersion  明确的航线版本（不是当前版本返回 409）
 * @param startAt       占用起点（含），epoch 毫秒（UTC）
 * @param endAt         占用终点（不含），epoch 毫秒（UTC）
 * @param altitudeLower 占用高度下限（含），单位米
 * @param altitudeUpper 占用高度上限（不含），单位米
 * @param yieldable     常规占用是否可被应急走廊强制让渡；false 表示不可让渡
 * @param requestId     写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyCreateRequest(
        @NotBlank @Size(max = 64) String occupancyId,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull @Min(0) Long startAt,
        @NotNull @Min(0) Long endAt,
        @NotNull @Min(0) @Max(100000) Integer altitudeLower,
        @NotNull @Min(0) @Max(100000) Integer altitudeUpper,
        @NotNull Boolean yieldable,
        @NotBlank @Size(max = 64) String requestId) {
}
