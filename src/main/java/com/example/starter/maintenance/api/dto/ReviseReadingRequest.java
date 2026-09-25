package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 修订读数请求：只改变累计工时，不改采样时刻；作为历史保养锚点的读数不可修订（409）。
 * 取值规则与新增读数一致：cumulativeValue（unit 单位十进制）优先，cumulativeMinutes 兼容。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param unit               读数值单位：MINUTES 或 HOURS；缺省为设备登记单位
 * @param cumulativeValue    修订后的累计工时（unit 单位十进制，最多 2 位小数），非负
 * @param cumulativeMinutes  修订后的累计工时（分钟），非负整数（兼容字段）
 */
public record ReviseReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @Pattern(regexp = "MINUTES|HOURS", message = "must be MINUTES or HOURS") String unit,
        @PositiveOrZero BigDecimal cumulativeValue,
        @PositiveOrZero Long cumulativeMinutes) {
}
