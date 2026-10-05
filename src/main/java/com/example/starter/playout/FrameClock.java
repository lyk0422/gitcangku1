package com.example.starter.playout;

/**
 * 帧时钟：以业务日零点（Asia/Shanghai）为原点，帧号 f 的真实时刻为 f * den / num 秒。
 *
 * <p>所有比较与换算基于有理数精确计算（帧号乘分母除分子），不逐帧累加舍入；
 * 只有面向毫秒坐标的显示映射才向下取整。支持的帧率：24/1、25/1、30000/1001。
 * 例如 30000/1001 下第 30000 帧恰为 1001 秒（30000 * 1001 / 30000）。</p>
 *
 * @param num 帧率分子（每秒帧数的分子），正整数
 * @param den 帧率分母，正整数
 */
public record FrameClock(int num, int den) {

    /** 一天的秒数。 */
    private static final long DAY_SECONDS = 86_400L;

    /** 支持的帧率：24/1。 */
    public static final FrameClock FPS_24 = new FrameClock(24, 1);
    /** 支持的帧率：25/1。 */
    public static final FrameClock FPS_25 = new FrameClock(25, 1);
    /** 支持的帧率：30000/1001（约 29.97fps，NTSC 下拉帧率）。 */
    public static final FrameClock FPS_30000_1001 = new FrameClock(30000, 1001);

    /**
     * 按分子/分母构造帧时钟；仅接受 24/1、25/1、30000/1001。
     *
     * @throws IllegalArgumentException 帧率不在支持列表内
     */
    public static FrameClock of(int num, int den) {
        if (num == 24 && den == 1) {
            return FPS_24;
        }
        if (num == 25 && den == 1) {
            return FPS_25;
        }
        if (num == 30000 && den == 1001) {
            return FPS_30000_1001;
        }
        throw new IllegalArgumentException("不支持的帧率: " + num + "/" + den);
    }

    /**
     * 一天内允许的最大结束帧号（含）：endFrame * den &lt;= 86400 * num。
     * 片段有理数结束不得超过一天。
     */
    public long maxEndFrame() {
        return DAY_SECONDS * num / den;
    }

    /** 帧号 f 的真实时刻是否不超过一天结束（f * den &lt;= 86400 * num）。 */
    public boolean withinDay(long frame) {
        return frame >= 0 && frame <= maxEndFrame();
    }

    /**
     * 帧号 f 映射到毫秒坐标的显示值：floor(f * den * 1000 / num)，仅用于显示，
     * 业务判定一律使用 {@link #frameLeMs} / {@link #frameGeMs} 等有理数比较。
     */
    public long floorMs(long frame) {
        return frame * den * 1_000L / num;
    }

    /** 帧时刻 f * den / num 秒是否 &lt;= 毫秒时刻 ms / 1000 秒（有理数精确比较）。 */
    public boolean frameLeMs(long frame, long ms) {
        return frame * den * 1_000L <= ms * (long) num;
    }

    /** 帧时刻 f * den / num 秒是否 &gt;= 毫秒时刻 ms / 1000 秒（有理数精确比较）。 */
    public boolean frameGeMs(long frame, long ms) {
        return frame * den * 1_000L >= ms * (long) num;
    }

    /**
     * 帧区间时长（frames 帧）是否不超过给定毫秒时长（有理数精确比较）：
     * frames * den * 1000 &lt;= durationMs * num。durationMs 达到一天时直接视为覆盖。
     */
    public boolean framesWithinDuration(long frames, long durationMs) {
        if (durationMs >= DAY_SECONDS * 1_000L) {
            return true;
        }
        return frames * den * 1_000L <= durationMs * (long) num;
    }
}
