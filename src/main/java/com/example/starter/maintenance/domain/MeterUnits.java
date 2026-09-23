package com.example.starter.maintenance.domain;

/**
 * 累计工时单位换算。规范精确值为毫秒：1 分钟 = 60000 毫秒、0.001 小时 = 3600 毫秒，
 * 两种来源均可精确互转；分钟视图按四舍五入（HALF_UP）从毫秒派生。
 */
public final class MeterUnits {

    /** 1 分钟对应的毫秒数。 */
    public static final long MILLIS_PER_MINUTE = 60_000L;

    /** 0.001 小时（1 毫小时）对应的毫秒数，漂移修正的精度单位。 */
    public static final long MILLIS_PER_MILLI_HOUR = 3_600L;

    private MeterUnits() {
    }

    /** 分钟 → 毫秒（精确）。 */
    public static long minutesToMillis(long minutes) {
        return Math.multiplyExact(minutes, MILLIS_PER_MINUTE);
    }

    /** 毫秒 → 分钟，四舍五入（HALF_UP），仅用于分钟粒度视图；入参须非负。 */
    public static long millisToMinutesRounded(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("累计工时毫秒数须非负：" + millis);
        }
        return Math.floorDiv(millis + MILLIS_PER_MINUTE / 2, MILLIS_PER_MINUTE);
    }

    /** 毫小时（0.001 小时）→ 毫秒（精确）。 */
    public static long milliHoursToMillis(long milliHours) {
        return Math.multiplyExact(milliHours, MILLIS_PER_MILLI_HOUR);
    }

    /** 毫秒 → 毫小时；入参须为 0.001 小时对齐的值（漂移修正结果），否则抛出异常。 */
    public static long millisToMilliHoursExact(long millis) {
        if (millis % MILLIS_PER_MILLI_HOUR != 0) {
            throw new IllegalArgumentException("毫秒值未对齐 0.001 小时：" + millis);
        }
        return millis / MILLIS_PER_MILLI_HOUR;
    }
}
