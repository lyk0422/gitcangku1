package com.example.starter.excursion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 越限区间计算：仅基于请求固定的引用集合，不补查集合外读数。
 *
 * <p>规则：按时刻升序排列；严格大于阈值 H 的读数，在分钟级相邻（恰好相差 1 分钟）时构成连续段；
 * 段长达到 N 即产生一个区间，到首个非超限读数或缺失分钟结束；
 * 区间左闭右开，右端为该段最后一个超限时刻加 1 分钟。阈值相等不算越限。
 */
public final class ExcursionIntervals {

    private ExcursionIntervals() {
    }

    /**
     * 一个越限区间：左闭右开 [startUtc, endUtc)。
     *
     * @param startUtc 段内首个超限时刻（UTC，ISO-8601）
     * @param endUtc   段内最后超限时刻加 1 分钟（UTC，ISO-8601）
     */
    public record Interval(String startUtc, String endUtc) {
    }

    /**
     * 参与计算的单条读数：UTC 整分钟时刻与十进制读数。
     */
    public record Point(Instant minuteUtc, BigDecimal reading) {
    }

    /**
     * 计算越限区间。输入点集先按时刻升序排序；同一时刻重复出现视为请求构造错误，由调用方保证唯一。
     *
     * @param points             引用集合内的读数点（仅这些点参与连续性判定）
     * @param threshold          阈值 H，严格大于才算越限
     * @param consecutiveMinutes 连续分钟数 N（2 至 5）
     * @return 按开始时刻升序的区间列表，可能为空
     */
    public static List<Interval> compute(List<Point> points, BigDecimal threshold, int consecutiveMinutes) {
        List<Point> sorted = points.stream()
                .sorted(Comparator.comparing(Point::minuteUtc))
                .toList();
        List<Interval> intervals = new ArrayList<>();
        List<Point> run = new ArrayList<>();
        for (Point point : sorted) {
            boolean exceeding = point.reading().compareTo(threshold) > 0;
            boolean adjacent = !run.isEmpty()
                    && run.get(run.size() - 1).minuteUtc().plusSeconds(60).equals(point.minuteUtc());
            if (exceeding && (run.isEmpty() || adjacent)) {
                run.add(point);
            } else {
                flush(run, consecutiveMinutes, intervals);
                run.clear();
                if (exceeding) {
                    run.add(point);
                }
            }
        }
        flush(run, consecutiveMinutes, intervals);
        return intervals;
    }

    /**
     * 结束一个连续超限段：段长达到 N 时追加区间 [段首时刻, 段末时刻+1分钟)。
     */
    private static void flush(List<Point> run, int consecutiveMinutes, List<Interval> intervals) {
        if (run.size() >= consecutiveMinutes) {
            Instant start = run.get(0).minuteUtc();
            Instant endExclusive = run.get(run.size() - 1).minuteUtc().plusSeconds(60);
            intervals.add(new Interval(start.toString(), endExclusive.toString()));
        }
    }
}
