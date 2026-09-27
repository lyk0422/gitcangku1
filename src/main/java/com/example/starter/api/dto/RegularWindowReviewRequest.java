package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 携带 UTC 左闭右开时空窗的常规审查请求。
 * 版本裁决与禁飞裁决外，时空窗命中任一有效应急走廊时返回 409 并给出走廊标识。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param startMs         审查时间窗起点（含），epoch 毫秒 UTC
 * @param endMs           审查时间窗终点（不含），epoch 毫秒 UTC
 * @param altMin          审查高度下界（含），米
 * @param altMax          审查高度上界（不含），米
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RegularWindowReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        @NotBlank @Size(max = 64) String requestId) {
}
