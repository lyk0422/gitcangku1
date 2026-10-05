package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 修订读数请求：只改变累计分钟与（可选的）负荷权重档位，不改采样时刻；
 * 作为历史保养锚点的读数不可修订（409）。更正后按当前读数序列整体重算等效累计，
 * 被更正读数与后一条间隔的等效贡献同步变化。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param cumulativeMinutes  修订后的累计工时（分钟），非负整数，须同时符合前后相邻读数值
 * @param loadWeight         修订后的负荷权重档位（NORMAL/HEAVY）；缺省表示不修改
 */
public record ReviseReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @PositiveOrZero Long cumulativeMinutes,
        @Pattern(regexp = "NORMAL|HEAVY", message = "须为 NORMAL 或 HEAVY") String loadWeight) {
}
