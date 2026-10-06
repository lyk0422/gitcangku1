package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.SegmentRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 字幕文档时间轴与阅读速率校验。
 * 时段为相对零点的非负起止毫秒，区间左闭右开、长度至少 500 毫秒，同文档各段绝对区间无重叠；
 * 译文去掉 Unicode 空白后按码点计数，阅读速率不得超过每秒 15 码点，
 * 用计数乘 1000 与时长乘 15 精确比较，全部使用 long 精确运算，溢出即拒绝。
 */
public final class SubtitleTiming {

    /** 字幕段最短时长（毫秒）。 */
    public static final long MIN_DURATION_MS = 500L;

    /** 阅读速率上限（码点/秒）。 */
    public static final long MAX_CODE_POINTS_PER_SECOND = 15L;

    private SubtitleTiming() {
    }

    /** 校验单个字幕段时段参数齐全且合法；不合法抛 422。 */
    public static void validateTiming(String segmentId, Long zeroPointMs, Long startMs, Long endMs) {
        if (zeroPointMs == null || startMs == null || endMs == null) {
            throw ApiException.unprocessable("字幕段缺少时段参数 zeroPointMs/startMs/endMs: " + segmentId);
        }
        if (startMs < 0 || endMs < 0) {
            throw ApiException.unprocessable("字幕段起止毫秒不能为负: " + segmentId);
        }
        long duration = endMs - startMs;
        if (duration < MIN_DURATION_MS) {
            throw ApiException.unprocessable("字幕段时长至少 " + MIN_DURATION_MS + " 毫秒: " + segmentId
                    + " 实际 " + duration + " 毫秒");
        }
        try {
            Math.multiplyExact(duration, MAX_CODE_POINTS_PER_SECOND);
        } catch (ArithmeticException e) {
            throw ApiException.unprocessable("字幕段时长超出可精确计算范围: " + segmentId);
        }
    }

    /**
     * 校验整段时间轴：每段时段合法，且按绝对区间（零点+起止）排序后同文档各段无重叠。
     * 区间左闭右开，首尾相接不算重叠；零点加法溢出抛 422。
     */
    public static void validateTimeline(List<SegmentRow> segments) {
        List<long[]> absolute = new ArrayList<>();
        for (SegmentRow segment : segments) {
            validateTiming(segment.segmentId(), segment.zeroPointMs(), segment.startMs(), segment.endMs());
            absolute.add(new long[]{
                    addExact(segment.zeroPointMs(), segment.startMs(), segment.segmentId()),
                    addExact(segment.zeroPointMs(), segment.endMs(), segment.segmentId())});
        }
        absolute.sort(Comparator.comparingLong(interval -> interval[0]));
        for (int i = 1; i < absolute.size(); i++) {
            if (absolute.get(i)[0] < absolute.get(i - 1)[1]) {
                throw ApiException.unprocessable("字幕段时间轴存在重叠区间");
            }
        }
    }

    /** 译文去 Unicode 空白后的码点数；补充平面字符（如表情）计 1 码点，不按 UTF-16 字符计。 */
    public static long codePointCount(String content) {
        return content.codePoints().filter(cp -> !isUnicodeWhitespace(cp)).count();
    }

    /** Unicode 空白：Character.isWhitespace 与 Unicode 空格字符（如  ）均计入。 */
    public static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** 阅读速率校验：计数乘 1000 大于时长乘 15 即超速，抛 422。 */
    public static void checkReadingSpeed(String content, long durationMs, String context) {
        long count = codePointCount(content);
        long limit = Math.multiplyExact(durationMs, MAX_CODE_POINTS_PER_SECOND);
        if (count * 1000L > limit) {
            throw ApiException.unprocessable("阅读速率超限: " + context + " 共 " + count
                    + " 码点 / " + durationMs + " 毫秒，超过每秒 " + MAX_CODE_POINTS_PER_SECOND + " 码点");
        }
    }

    private static long addExact(long zeroPointMs, long offsetMs, String segmentId) {
        try {
            return Math.addExact(zeroPointMs, offsetMs);
        } catch (ArithmeticException e) {
            throw ApiException.unprocessable("字幕段绝对时间超出可精确计算范围: " + segmentId);
        }
    }
}
