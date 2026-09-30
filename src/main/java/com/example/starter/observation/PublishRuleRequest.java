package com.example.starter.observation;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标基准转换规则发布请求。版本号由调用方指定且全局唯一，发布后不可变、不可删除。
 *
 * @param ruleVersion      规则版本号，正整数，全局唯一
 * @param targetFrame      目标坐标基准标识
 * @param offsetX          X 方向偏移量（目标基准长度单位）
 * @param offsetY          Y 方向偏移量（目标基准长度单位）
 * @param effectiveFromUtc 生效起点（UTC，含）
 * @param effectiveToUtc   生效终点（UTC，不含）；为 null 表示自起点起长期有效；非 null 时必须严格晚于起点
 */
public record PublishRuleRequest(
        @NotNull @Min(1) Integer ruleVersion,
        @NotBlank @Size(max = 64) String targetFrame,
        @NotNull @Digits(integer = 9, fraction = 9) BigDecimal offsetX,
        @NotNull @Digits(integer = 9, fraction = 9) BigDecimal offsetY,
        @NotNull Instant effectiveFromUtc,
        Instant effectiveToUtc) {
}
