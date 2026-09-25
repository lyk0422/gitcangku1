package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增工时读数请求。允许补录历史读数，但按采样时刻排序后累计分钟须单调不减。
 * 可附带单位标签：与设备登记单位一致时按登记单位解释；不同时必须提供 cumulativeValue，
 * 由服务端按 BigDecimal 规则换算为设备单位后参与单调性比较，并记录换算留痕。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param cumulativeMinutes  累计工时（分钟），非负整数；设备单位为 MINUTES 且未附带单位标签时必填
 * @param unit               可选单位标签（MINUTES/HOURS），缺省按设备登记单位解释
 * @param cumulativeValue    累计工时（按 unit 指定的单位，十进制最多 2 位小数）
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @PositiveOrZero Long cumulativeMinutes,
        String unit,
        @PositiveOrZero BigDecimal cumulativeValue) {
}
