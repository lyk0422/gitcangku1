package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 空域占用登记请求（常规或应急）。
 * 时间窗 [startUtc, endUtc) 左闭右开（UTC），高度窗 [altMin, altMax) 左闭右开。
 * 常规占用命中有效应急走廊返回 409；应急占用可使用走廊段，但 reviewId 必须为
 * CLEAR 的禁飞区审查（不能以走廊绕过禁区）。
 *
 * @param occupancyId 占用唯一标识（客户端指定）
 * @param kind        占用类型：REGULAR 常规 / EMERGENCY 应急
 * @param routeId     航线标识
 * @param reviewId    依据的不可变审查标识（结论必须 CLEAR）
 * @param startUtc    占用起点 epoch 毫秒（UTC，含）
 * @param endUtc      占用终点 epoch 毫秒（UTC，不含）
 * @param altMin      最低高度（含），米
 * @param altMax      最高高度（不含），米
 * @param yieldable   常规占用是否允许被应急走廊让渡；kind=REGULAR 时必填，
 *                    kind=EMERGENCY 时必须为 null（应急占用不参与让渡）
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record OccupancyCreateRequest(
        @NotBlank @Size(max = 64) String occupancyId,
        @NotBlank @Size(max = 16) String kind,
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 64) String reviewId,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        @NotNull Integer altMin,
        @NotNull Integer altMax,
        Boolean yieldable,
        @NotBlank @Size(max = 64) String requestId) {
}
