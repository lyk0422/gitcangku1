package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 修订读数请求：只改变累计工时，不改采样时刻；作为历史保养锚点的读数不可修订（409）。
 * 单位换算规则与新增读数一致：附带单位标签与设备登记单位不同时换算并留痕。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param cumulativeMinutes  修订后的累计工时（分钟），非负整数；设备单位为 MINUTES 且未附带单位标签时必填
 * @param unit               可选单位标签（MINUTES/HOURS），缺省按设备登记单位解释
 * @param cumulativeValue    修订后的累计工时（按 unit 指定的单位，十进制最多 2 位小数）
 */
public record ReviseReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @PositiveOrZero Long cumulativeMinutes,
        String unit,
        @PositiveOrZero BigDecimal cumulativeValue) {
}
