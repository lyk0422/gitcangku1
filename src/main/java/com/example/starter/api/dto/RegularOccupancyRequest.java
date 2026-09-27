package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 常规航线时空占用登记请求。服务在同一事务内先完成携带时空窗的常规审查
 * （命中有效应急走廊返回 409），通过后才写入占用。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param startMs         占用时间窗起点（含），epoch 毫秒 UTC
 * @param endMs           占用时间窗终点（不含），epoch 毫秒 UTC
 * @param altMin          占用高度下界（含），米
 * @param altMax          占用高度上界（不含），米
 * @param yieldable       是否可让渡：false 时走廊建立遇到该占用整次 422
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RegularOccupancyRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        @NotNull Boolean yieldable,
        @NotBlank @Size(max = 64) String requestId) {
}
