package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增运动间隔许可请求。引用现有两点航线的明确版本与明确的空域版本，
 * 在 [startEpochSec, endEpochSec)（左闭右开，UTC 整秒）内沿直线匀速运动。
 *
 * @param routeId         航线唯一标识（必须是两点航线）
 * @param routeVersion    明确的航线版本（必须是当前版本）
 * @param airspaceVersion 明确的空域版本（必须是当前版本）
 * @param startEpochSec   有效起点（含），UTC epoch 整秒
 * @param endEpochSec     有效终点（不含），UTC epoch 整秒，必须大于起点
 * @param operator        操作者标识（参与幂等身份）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record PermitCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotNull Long startEpochSec,
        @NotNull Long endEpochSec,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}