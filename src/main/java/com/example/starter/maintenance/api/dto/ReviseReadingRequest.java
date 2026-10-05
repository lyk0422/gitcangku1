package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 修订读数请求：只改变累计分钟，不改采样时刻与负荷类型；作为历史保养锚点的读数不可修订（409）。
 * 修订后本读数区间与后一条读数区间的等效贡献都会按新值重算。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param operator           操作者标识（可选，纳入幂等身份）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param cumulativeMinutes  修订后的累计工时（分钟），非负整数，须同时符合前后相邻读数值
 */
public record ReviseReadingRequest(
        @NotBlank String requestId,
        String operator,
        @NotNull Long expectedVersion,
        @NotNull @PositiveOrZero Long cumulativeMinutes) {
}
