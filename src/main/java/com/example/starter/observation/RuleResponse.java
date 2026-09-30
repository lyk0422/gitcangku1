package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标基准转换规则响应：返回规则版本、目标基准、X/Y 偏移量与 UTC 生效区间（左闭右开）。
 *
 * @param ruleVersion      规则版本号
 * @param targetFrame      目标坐标基准标识
 * @param offsetX          X 方向偏移量（目标基准长度单位，九位小数规范化）
 * @param offsetY          Y 方向偏移量（目标基准长度单位，九位小数规范化）
 * @param effectiveFromUtc 生效起点（UTC，含）
 * @param effectiveToUtc   生效终点（UTC，不含）；null 表示长期有效
 */
public record RuleResponse(
        int ruleVersion,
        String targetFrame,
        BigDecimal offsetX,
        BigDecimal offsetY,
        Instant effectiveFromUtc,
        Instant effectiveToUtc) {

    /**
     * 由持久化规则构造响应。
     */
    public static RuleResponse of(CoordinateRule rule) {
        return new RuleResponse(rule.ruleVersion(), rule.targetFrame(),
                rule.offsetX(), rule.offsetY(),
                rule.effectiveFromUtc(), rule.effectiveToUtc());
    }
}
