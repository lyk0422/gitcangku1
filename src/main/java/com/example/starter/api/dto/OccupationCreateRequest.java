package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建空域占用请求。创建时在同一事务内完成禁飞审查与走廊裁决：
 * 常规占用命中有效走廊返回 409；应急占用仍须通过禁飞审查。
 * 时段与高度范围均为左闭右开区间，时间统一为 UTC epoch 毫秒。
 *
 * @param requestId    写操作全局唯一请求标识，用于幂等重放
 * @param occupationId 占用唯一标识
 * @param routeId      占用航线标识
 * @param routeVersion 明确的航线版本（须为当前版本）
 * @param kind         占用类型：REGULAR / EMERGENCY
 * @param startUtc     占用起点（含），epoch 毫秒（UTC）
 * @param endUtc       占用终点（不含），epoch 毫秒（UTC）
 * @param altMin       占用高度下限（含），单位米
 * @param altMax       占用高度上限（不含），单位米
 * @param yieldable    是否可被应急走廊强制让渡；false 表示不可让渡
 */
public record OccupationCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String occupationId,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotBlank @Pattern(regexp = "REGULAR|EMERGENCY") String kind,
        @NotNull @Min(0) Long startUtc,
        @NotNull @Min(0) Long endUtc,
        @NotNull @Min(0) @Max(100000) Integer altMin,
        @NotNull @Min(0) @Max(100000) Integer altMax,
        boolean yieldable) {
}
