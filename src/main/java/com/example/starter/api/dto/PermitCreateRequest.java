package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建运动间隔许可请求。引用现有两点航线的明确版本与 UTC 整秒起止时刻，
 * 期间沿两点连线匀速运动；有效区间左闭右开。
 *
 * @param requestId        写操作全局唯一请求标识，用于幂等重放
 * @param operator         申请操作者
 * @param routeId          航线唯一标识（资源）
 * @param routeVersion     期望的当前航线版本（不是当前版本返回 409）
 * @param startEpochSecond 生效起始时刻，UTC epoch 整秒（含）
 * @param endEpochSecond   生效结束时刻，UTC epoch 整秒（不含），必须大于起始时刻
 */
public record PermitCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long startEpochSecond,
        @NotNull Long endEpochSecond) {
}