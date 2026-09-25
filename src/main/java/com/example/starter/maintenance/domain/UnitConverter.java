package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 计量单位换算器：系统内部统一以分钟（四舍五入到最近整数）为判定口径。
 * 全部换算使用 BigDecimal 精确计算；展示层换算与存储层换算使用同一规则，
 * 展示值直接由存储分钟数换算，避免两次转换造成误差累积。
 */
public final class UnitConverter {

    /** 登记单位十进制值的小数位数上限。 */
    public static final int VALUE_SCALE = 2;

    private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal("60");

    private UnitConverter() {
    }

    /**
     * 将以 {@code unit} 表示的十进制值换算为分钟，四舍五入（HALF_UP）到最近整数分钟。
     * HOURS 乘以 60；MINUTES 直接取整。
     */
    public static long toMinutes(BigDecimal value, MeasurementUnit unit) {
        BigDecimal minutes = switch (unit) {
            case MINUTES -> value;
            case HOURS -> value.multiply(MINUTES_PER_HOUR);
        };
        return minutes.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 将以 {@code sourceUnit} 表示的十进制值换算为 {@code targetUnit} 表示，
     * 结果保留 {@link #VALUE_SCALE} 位小数（HALF_UP）。
     */
    public static BigDecimal convert(BigDecimal value, MeasurementUnit sourceUnit,
                                     MeasurementUnit targetUnit) {
        if (sourceUnit == targetUnit) {
            return normalize(value);
        }
        BigDecimal converted = switch (targetUnit) {
            case MINUTES -> value.multiply(MINUTES_PER_HOUR);
            case HOURS -> value.divide(MINUTES_PER_HOUR, VALUE_SCALE, RoundingMode.HALF_UP);
        };
        return normalize(converted);
    }

    /** 展示层换算：由存储分钟数直接换算为目标单位的十进制展示值（同一 BigDecimal 规则）。 */
    public static BigDecimal displayFromMinutes(long minutes, MeasurementUnit displayUnit) {
        return convert(BigDecimal.valueOf(minutes), MeasurementUnit.MINUTES, displayUnit);
    }

    /** 规整为最多 {@link #VALUE_SCALE} 位小数；超出位数时按 HALF_UP 取舍。 */
    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(VALUE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** 校验十进制值的小数位数不超过 {@link #VALUE_SCALE} 位。 */
    public static boolean hasValidScale(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= VALUE_SCALE;
    }
}
