package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增工时读数请求。允许补录历史读数，但按采样时刻排序后换算分钟数须单调不减。
 * 读数值可用 cumulativeValue（unit 指定单位的十进制，最多 2 位小数）或
 * cumulativeMinutes（分钟整数，兼容旧调用）提供，二者至少其一；
 * 同时提供时以 cumulativeValue 为准。unit 缺省为设备登记单位；
 * 与登记单位不同时服务端换算为登记单位存储并留痕。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param unit               读数值单位：MINUTES 或 HOURS；缺省为设备登记单位
 * @param cumulativeValue    累计工时（unit 单位十进制，最多 2 位小数），非负
 * @param cumulativeMinutes  累计工时（分钟），非负整数（兼容字段）
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @Pattern(regexp = "MINUTES|HOURS", message = "must be MINUTES or HOURS") String unit,
        @PositiveOrZero BigDecimal cumulativeValue,
        @PositiveOrZero Long cumulativeMinutes) {
}
