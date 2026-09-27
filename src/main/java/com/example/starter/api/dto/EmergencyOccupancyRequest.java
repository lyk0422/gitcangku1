package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 应急航线时空占用登记请求。必须显式命中指定有效走廊，且应急审查仍须通过禁飞审查，
 * 不能以走廊绕过禁区；审查、走廊命中裁决与占用写入在同一事务内完成。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param startMs         占用时间窗起点（含），epoch 毫秒 UTC
 * @param endMs           占用时间窗终点（不含），epoch 毫秒 UTC
 * @param altMin          占用高度下界（含），米
 * @param altMax          占用高度上界（不含），米
 * @param corridorId      必须命中的有效应急走廊标识
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record EmergencyOccupancyRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        @NotBlank @Size(max = 64) String corridorId,
        @NotBlank @Size(max = 64) String requestId) {
}
