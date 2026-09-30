package com.example.starter.observation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 坐标基准转换的纯逻辑：规则生效判定、坐标转换与站点边界判断。
 *
 * <p>不访问数据库，便于对左闭右开区间、六位小数 HALF_UP 与边界点做确定性单元测试；
 * SQL 层的区间命中另由真实 H2 测试验证。
 */
final class CoordinateFrames {

    /**
     * 目标坐标保存精度：六位小数，四舍五入使用 HALF_UP（等距时远离零）。
     */
    static final int TARGET_SCALE = 6;

    /**
     * 站点矩形边界（目标基准长度单位，含边界点）：X ∈ [0, 10]，Y ∈ [0, 30]。
     */
    static final BigDecimal SITE_MIN_X = BigDecimal.ZERO;
    static final BigDecimal SITE_MAX_X = BigDecimal.TEN;
    static final BigDecimal SITE_MIN_Y = BigDecimal.ZERO;
    static final BigDecimal SITE_MAX_Y = new BigDecimal("30");

    private CoordinateFrames() {
    }

    /**
     * 判断规则在指定 UTC 时刻是否生效：区间左闭右开 [from, to)；to 为 null 表示长期有效。
     */
    static boolean isEffective(CoordinateRule rule, Instant at) {
        boolean fromInclusive = !at.isBefore(rule.effectiveFromUtc());
        boolean toExclusive = rule.effectiveToUtc() == null || at.isBefore(rule.effectiveToUtc());
        return fromInclusive && toExclusive;
    }

    /**
     * 返回在指定时刻生效的规则，按版本号升序；结果为 0 条或多于 1 条时调用方应返回 422。
     */
    static List<CoordinateRule> effectiveRules(List<CoordinateRule> rules, Instant at) {
        return rules.stream()
                .filter(rule -> isEffective(rule, at))
                .sorted(java.util.Comparator.comparingInt(CoordinateRule::ruleVersion))
                .toList();
    }

    /**
     * 目标坐标 = 原坐标 + 偏移量，结果按六位小数 HALF_UP 保存。
     */
    static BigDecimal convert(BigDecimal original, BigDecimal offset) {
        return original.add(offset).setScale(TARGET_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 判断目标坐标是否落在站点矩形内（边界点视为站内）：X ∈ [0, 10] 且 Y ∈ [0, 30]，按数值比较。
     */
    static boolean withinSite(BigDecimal targetX, BigDecimal targetY) {
        return inRange(targetX, SITE_MIN_X, SITE_MAX_X) && inRange(targetY, SITE_MIN_Y, SITE_MAX_Y);
    }

    private static boolean inRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
