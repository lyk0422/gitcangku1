package com.example.starter.exposure.exposure;

/**
 * 访客静默时段纯时间计算：UTC 时刻按访客固定偏移换算本地分钟并判定是否落入静默区间。
 *
 * <p>静默区间以本地分钟表示：起小于止为同日区间 {@code [起, 止)}；起大于止为跨零点区间
 * {@code [起, 1440) ∪ [0, 止)}。结束边界为排他：本地分钟恰好等于结束分钟时不再静默。
 * 所有方法无副作用、不依赖系统时钟，便于单元测试。</p>
 */
public final class QuietHoursCalculator {

    /** 一天的毫秒数。 */
    static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    /** 一分钟的毫秒数。 */
    static final long MINUTE_MILLIS = 60_000L;

    private QuietHoursCalculator() {
    }

    /**
     * 将 UTC 毫秒时刻按偏移换算为访客本地分钟（0～1439）。
     * 使用 floor 除法，兼容负偏移（如 -720）下的跨日换算。
     */
    public static int localMinute(long nowUtcMillis, int utcOffsetMinutes) {
        long localMillis = nowUtcMillis + utcOffsetMinutes * MINUTE_MILLIS;
        long minuteSinceEpoch = Math.floorDiv(localMillis, MINUTE_MILLIS);
        return (int) Math.floorMod(minuteSinceEpoch, 1440L);
    }

    /**
     * 判定给定本地分钟是否落在静默区间内（结束分钟排他）。
     *
     * @param localMinute 当前本地分钟 0～1439
     * @param startMinute 静默开始本地分钟
     * @param endMinute   静默结束本地分钟，与开始不同
     */
    public static boolean isWithinQuietHours(int localMinute, int startMinute, int endMinute) {
        if (startMinute < endMinute) {
            return localMinute >= startMinute && localMinute < endMinute;
        }
        // 起大于止：跨零点
        return localMinute >= startMinute || localMinute < endMinute;
    }

    /**
     * 计算当前时刻之后最近一次静默结束的 UTC 毫秒时刻（即响应中的 quietUntilUtc）。
     *
     * <p>结束时刻按访客本地日历每日重复，再按偏移反算回 UTC；在 -1/0/+1 三个本地日候选中
     * 取严格晚于当前时刻的最小值。调用方应仅在确认当前处于静默区间时调用。</p>
     */
    public static long nextQuietEndUtcMillis(long nowUtcMillis, int utcOffsetMinutes,
                                             int startMinute, int endMinute) {
        long offsetMs = utcOffsetMinutes * MINUTE_MILLIS;
        long localMillis = nowUtcMillis + offsetMs;
        long localDayStart = Math.floorDiv(localMillis, DAY_MILLIS) * DAY_MILLIS;
        long best = Long.MAX_VALUE;
        for (int dayOffset = -1; dayOffset <= 1; dayOffset++) {
            long endLocalMillis = localDayStart + dayOffset * DAY_MILLIS + endMinute * MINUTE_MILLIS;
            long endUtcMillis = endLocalMillis - offsetMs;
            if (endUtcMillis > nowUtcMillis && endUtcMillis < best) {
                best = endUtcMillis;
            }
        }
        if (best == Long.MAX_VALUE) {
            // 极端偏移下三候选不足：向前/向后整日扩展搜索
            for (int dayOffset = -2; dayOffset <= 2; dayOffset += 4) {
                long endLocalMillis = localDayStart + dayOffset * DAY_MILLIS + endMinute * MINUTE_MILLIS;
                long endUtcMillis = endLocalMillis - offsetMs;
                if (endUtcMillis > nowUtcMillis && endUtcMillis < best) {
                    best = endUtcMillis;
                }
            }
        }
        return best;
    }
}
