package com.example.starter.playout;

import com.example.starter.playout.api.ApiException;

/**
 * 帧时钟帧率：分子/分母表示每秒帧数（如 30000/1001）。真实时刻按
 * 帧号 × 分母 / 分子 秒 一次性计算，不逐帧累加舍入，避免累计误差：
 * 30000 帧 @ 30000/1001 恰为 1001 秒。
 *
 * <p>所有比较均以有理数精确进行（交叉相乘，整数运算）；映射到毫秒输出时仅显示值向下取整。</p>
 */
public record FrameRate(int numerator, int denominator) {

    /** 一天的秒数。 */
    private static final long DAY_SECONDS = 86400L;

    /** 支持的帧率：24/1、25/1、30000/1001。 */
    public static final FrameRate FPS_24 = new FrameRate(24, 1);
    public static final FrameRate FPS_25 = new FrameRate(25, 1);
    public static final FrameRate FPS_30000_1001 = new FrameRate(30000, 1001);

    /** 解析并校验帧率；不在支持集合内时抛 400。 */
    public static FrameRate of(int numerator, int denominator) {
        FrameRate rate = new FrameRate(numerator, denominator);
        if (!rate.equals(FPS_24) && !rate.equals(FPS_25) && !rate.equals(FPS_30000_1001)) {
            throw ApiException.badRequest(
                    "不支持的帧率: " + numerator + "/" + denominator + "，仅支持 24/1、25/1、30000/1001");
        }
        return rate;
    }

    /**
     * 帧号对应的业务日偏移毫秒，向下取整（仅用于显示与保守 SQL 预筛）。
     * 真实值为 frame * denominator * 1000 / numerator 毫秒。
     */
    public long floorMs(long frame) {
        return frame * denominator * 1000L / numerator;
    }

    /** 帧号对应的业务日偏移毫秒，向上取整（与 floorMs 配合做保守 SQL 预筛）。 */
    public long ceilMs(long frame) {
        long dividend = frame * denominator * 1000L;
        return (dividend + numerator - 1) / numerator;
    }

    /** 真实时刻 frame 帧 是否 <= 业务日偏移 posMs 毫秒（有理数精确比较）。 */
    public boolean realStartLte(long frame, long posMs) {
        return frame * denominator * 1000L <= posMs * numerator;
    }

    /** 真实时刻 frame 帧 是否 > 业务日偏移 posMs 毫秒（有理数精确比较）。 */
    public boolean realEndGt(long frame, long posMs) {
        return frame * denominator * 1000L > posMs * numerator;
    }

    /** 帧号对应的真实结束是否不超过一天（有理数精确：frame * denominator <= 86400 * numerator）。 */
    public boolean withinDay(long frame) {
        return frame * denominator <= DAY_SECONDS * numerator;
    }

    /** 片段时长（endFrame - startFrame 帧）是否不超过 durationMs 毫秒（有理数精确比较）。 */
    public boolean durationWithin(long startFrame, long endFrame, long durationMs) {
        return (endFrame - startFrame) * denominator * 1000L <= durationMs * numerator;
    }
}
