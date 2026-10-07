package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;

/**
 * 字幕时段与阅读速率校验工具。
 * 区间左闭右开、长度至少 500 毫秒；译文去掉 Unicode 空白后按码点计数，
 * 阅读速率不得超过每秒 15 码点，用计数乘 1000 与时长乘 15 精确比较。
 * 所有差值与乘法均显式防溢出：start/end 非负保证差值不溢出，
 * 时长乘 15 可能溢出时按必然通过处理（计数乘 1000 受 int 上界约束不会溢出）。
 */
public final class SubtitleTiming {

    /** 字幕段最短时长，毫秒。 */
    public static final long MIN_DURATION_MS = 500L;

    /** 阅读速率上限：每秒码点数。 */
    public static final long MAX_CODE_POINTS_PER_SECOND = 15L;

    private SubtitleTiming() {
    }

    /**
     * 校验字幕段时段并返回时长毫秒：startMs 非负、endMs 大于 startMs、时长至少 500 毫秒。
     * startMs/endMs 均非负且 endMs &gt; startMs，差值不会溢出。
     */
    public static long durationOf(long startMs, long endMs) {
        if (startMs < 0) {
            throw ApiException.unprocessable("startMs 不能为负数: " + startMs);
        }
        if (endMs <= startMs) {
            throw ApiException.unprocessable("endMs 必须大于 startMs: " + startMs + "/" + endMs);
        }
        long duration = endMs - startMs;
        if (duration < MIN_DURATION_MS) {
            throw ApiException.unprocessable("字幕段时长 " + duration + " 毫秒小于最短时长 "
                    + MIN_DURATION_MS + " 毫秒");
        }
        return duration;
    }

    /**
     * 计算字幕段绝对起止毫秒（相对零点 + 相对起止），用于跨段重叠比较。
     * 加法溢出时返回 422，不静默回绕。
     *
     * @return 二元数组 {绝对起始, 绝对结束}
     */
    public static long[] absoluteInterval(long zeroPointMs, long startMs, long endMs) {
        try {
            return new long[]{Math.addExact(zeroPointMs, startMs), Math.addExact(zeroPointMs, endMs)};
        } catch (ArithmeticException e) {
            throw ApiException.unprocessable("字幕时段参数超出可表示范围: zeroPointMs=" + zeroPointMs
                    + ", startMs=" + startMs + ", endMs=" + endMs);
        }
    }

    /**
     * 统计去掉 Unicode 空白后的码点数；补充平面字符（如表情）计 1 码点，不按 UTF-16 字符计。
     */
    public static long countCodePoints(String text) {
        return text.codePoints().filter(cp -> !isUnicodeWhitespace(cp)).count();
    }

    /** Unicode 空白判定：Character.isWhitespace（控制类空白）或 isSpaceChar（Zs 类分隔空格）。 */
    public static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /**
     * 阅读速率校验：计数乘 1000 与时长乘 15 精确比较，超过即 422。
     * 码点数受 String 长度上界约束，乘 1000 不溢出；时长乘 15 溢出时任何可达计数必然通过。
     */
    public static void assertReadingSpeed(long codePoints, long durationMs) {
        boolean withinLimit;
        if (durationMs > Long.MAX_VALUE / MAX_CODE_POINTS_PER_SECOND) {
            withinLimit = true;
        } else {
            withinLimit = codePoints * 1000L <= durationMs * MAX_CODE_POINTS_PER_SECOND;
        }
        if (!withinLimit) {
            throw ApiException.unprocessable("译文阅读超速: " + codePoints + " 码点 / " + durationMs
                    + " 毫秒，超过每秒 " + MAX_CODE_POINTS_PER_SECOND + " 码点");
        }
    }
}
