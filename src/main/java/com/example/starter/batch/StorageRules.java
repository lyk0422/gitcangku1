package com.example.starter.batch;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 仓储温度偏差判定纯规则（无 IO，便于单元测试覆盖边界）。
 * 温度单位℃；相邻读数缺测阈值为 <strong>严格大于 30 分钟</strong>，恰好 30 分钟不算缺测；
 * 越界为严格超出上下限，恰好等于限值不算越界。
 */
public final class StorageRules {

    /**
     * 相邻读数允许的最大采集间隔：30 分钟；间隔恰好等于该值合法。
     */
    public static final Duration MAX_READING_GAP = Duration.ofMinutes(30);

    private StorageRules() {
    }

    /**
     * 读数是否越界：temperature &lt; lowerLimit 或 temperature &gt; upperLimit；等于限值不越界。
     */
    public static boolean isOutOfLimit(BigDecimal temperature, BigDecimal upperLimit, BigDecimal lowerLimit) {
        return temperature.compareTo(upperLimit) > 0 || temperature.compareTo(lowerLimit) < 0;
    }

    /**
     * 相邻读数间隔是否构成缺测：间隔严格大于 30 分钟；前一读数不存在时不构成缺测。
     */
    public static boolean isMissingGap(Instant previous, Instant current) {
        if (previous == null || current == null) {
            return false;
        }
        return Duration.between(previous, current).compareTo(MAX_READING_GAP) > 0;
    }

    /**
     * 间隔秒数（current - previous）；前一读数不存在时返回 null。
     */
    public static Long gapSeconds(Instant previous, Instant current) {
        if (previous == null || current == null) {
            return null;
        }
        return Duration.between(previous, current).getSeconds();
    }
}
