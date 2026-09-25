package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 设备工时计量单位。设备登记时声明，登记后不可更改；保养周期与全部读数按登记单位记录。
 * 系统内部统一换算为整数分钟（BigDecimal 精确计算，四舍五入到最近整数分钟）进行判定。
 */
public enum MeterUnit {

    /** 分钟：1 单位 = 1 分钟。 */
    MINUTES(BigDecimal.ONE),

    /** 小时：1 单位 = 60 分钟。 */
    HOURS(new BigDecimal("60"));

    /** 展示层换算保留的小数位数（单位定义为十进制、最多 2 位小数）。 */
    public static final int DISPLAY_SCALE = 2;

    /** 存储层换算值保留的小数位数（避免存储即丢失精度）。 */
    public static final int STORE_SCALE = 6;

    private final BigDecimal minutesPerUnit;

    MeterUnit(BigDecimal minutesPerUnit) {
        this.minutesPerUnit = minutesPerUnit;
    }

    /**
     * 解析单位标签；非法标签返回 null（由调用方转换为 400）。
     */
    public static MeterUnit parse(String tag) {
        if (tag == null) {
            return null;
        }
        for (MeterUnit unit : values()) {
            if (unit.name().equals(tag)) {
                return unit;
            }
        }
        return null;
    }

    /**
     * 将以本单位表示的值换算为整数分钟：BigDecimal 精确乘以换算率，
     * 四舍五入（HALF_UP）到最近整数分钟。
     */
    public long toMinutes(BigDecimal value) {
        return value.multiply(minutesPerUnit).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 将整数分钟换算为本单位存储值（保留 {@link #STORE_SCALE} 位小数，HALF_UP）。
     * 仅用于存储与展示，不参与判定，避免多次转换误差累积。
     */
    public BigDecimal fromMinutes(long minutes) {
        BigDecimal stripped = BigDecimal.valueOf(minutes)
                .divide(minutesPerUnit, STORE_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        // stripTrailingZeros 对整数值会产生负 scale（如 1.5E+2），统一归一到 scale 0
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /**
     * 将整数分钟换算为本单位展示值（保留 {@link #DISPLAY_SCALE} 位小数，HALF_UP）。
     * 展示层一律从权威分钟数单次换算，不由其他展示值二次转换。
     */
    public BigDecimal displayFromMinutes(long minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(minutesPerUnit, DISPLAY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 校验用户声明值是否符合单位精度（十进制、最多 2 位小数）。
     */
    public static boolean hasAtMostTwoDecimals(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 2;
    }
}
