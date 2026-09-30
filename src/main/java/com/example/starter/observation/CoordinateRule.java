package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标基准转换规则（不可变）：对应 coordinate_rule 表一行。
 *
 * <p>生效区间为 UTC 左闭右开 [{@code effectiveFromUtc}, {@code effectiveToUtc})；
 * {@code effectiveToUtc} 为 null 表示自起点起长期有效。规则发布后不可修改、不可删除，
 * 历史观测冻结命中的规则版本与转换结果。
 *
 * @param ruleVersion      规则版本号，全局唯一，从 1 开始
 * @param targetFrame      目标坐标基准标识
 * @param offsetX          X 方向偏移量（目标基准长度单位），目标 X = 原 X + offsetX
 * @param offsetY          Y 方向偏移量（目标基准长度单位），目标 Y = 原 Y + offsetY
 * @param effectiveFromUtc 生效起点（UTC，含）
 * @param effectiveToUtc   生效终点（UTC，不含）；null 表示长期有效
 */
public record CoordinateRule(
        int ruleVersion,
        String targetFrame,
        BigDecimal offsetX,
        BigDecimal offsetY,
        Instant effectiveFromUtc,
        Instant effectiveToUtc) {
}
