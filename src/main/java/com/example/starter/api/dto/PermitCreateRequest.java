package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 创建两点匀速航线运动间隔许可请求。
 * 许可引用现有两点航线的明确版本与 UTC 整秒起止时刻，
 * 期间沿两航点连线匀速运动；有效区间左闭右开 [startSecond, endSecond)。
 *
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 * @param operator    操作者标识，参与幂等身份
 * @param routeId     引用的两点航线标识
 * @param routeVersion 期望的航线当前版本，不是当前版本返回 409
 * @param startSecond 有效起点（含），UTC epoch 整秒
 * @param endSecond   有效终点（不含），UTC epoch 整秒，必须大于 startSecond
 */
public record PermitCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull @PositiveOrZero Long startSecond,
        @NotNull @PositiveOrZero Long endSecond) {
}