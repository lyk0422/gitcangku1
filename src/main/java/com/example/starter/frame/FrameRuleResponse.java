package com.example.starter.frame;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 规则版本响应：发布结果与规则查询均返回规则版本完整内容。
 *
 * @param ruleId           规则链标识
 * @param version          规则链内版本号，从 1 开始
 * @param targetDatum      转换目标基准名称
 * @param offsetX          X 方向偏移量
 * @param offsetY          Y 方向偏移量
 * @param effectiveFromUtc 生效起始时刻（UTC，含）
 * @param effectiveToUtc   生效结束时刻（UTC，不含）；开放版本为 null
 */
public record FrameRuleResponse(
        String ruleId,
        int version,
        String targetDatum,
        BigDecimal offsetX,
        BigDecimal offsetY,
        Instant effectiveFromUtc,
        Instant effectiveToUtc) {

    /**
     * 由持久化规则构造响应。
     */
    public static FrameRuleResponse of(FrameRule rule) {
        return new FrameRuleResponse(rule.ruleId(), rule.version(), rule.targetDatum(),
                rule.offsetX(), rule.offsetY(), rule.effectiveFromUtc(), rule.effectiveToUtc());
    }
}
