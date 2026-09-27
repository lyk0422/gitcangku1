package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 已让渡航线显式重新申请请求。必须引用 YIELDED 原占用，重新进行携带时空窗的
 * 常规审查并显式申请；走廊仍有效且时空窗仍命中时返回 409。
 *
 * @param oldOccupancyId  原被让渡占用标识
 * @param routeVersion    重新审查明确指定的航线版本
 * @param airspaceVersion 重新审查明确指定的空域版本
 * @param startMs         新占用时间窗起点（含），epoch 毫秒 UTC
 * @param endMs           新占用时间窗终点（不含），epoch 毫秒 UTC
 * @param altMin          新占用高度下界（含），米
 * @param altMax          新占用高度上界（不含），米
 * @param yieldable       新占用是否可让渡
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record YieldedReapplyRequest(
        @NotBlank @Size(max = 64) String oldOccupancyId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        @NotNull Boolean yieldable,
        @NotBlank @Size(max = 64) String requestId) {
}
