package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 检验台容量规划纯逻辑（无 Spring 依赖，可直接单元测试）。
 * 时段一律 UTC 左闭右开 [start, end)：一方结束时刻等于另一方开始时刻属于首尾相接，不重叠。
 * 峰值占用采用扫描线：同一时刻先处理结束事件再处理开始事件，保证右开语义。
 */
public final class ReservationPlanner {

    private ReservationPlanner() {
    }

    /**
     * 左闭右开时段。
     *
     * @param start 开始时刻（含）
     * @param end   结束时刻（不含）
     */
    public record Interval(LocalDateTime start, LocalDateTime end) {
    }

    /**
     * 窗口内一段恒定占用量。
     *
     * @param start 分段开始时刻（含）
     * @param end   分段结束时刻（不含）
     * @param load  该分段内并行占用数量
     */
    public record Segment(LocalDateTime start, LocalDateTime end, int load) {
    }

    /**
     * 两个左闭右开时段是否重叠：startA &lt; endB 且 endA &gt; startB。
     */
    public static boolean overlaps(LocalDateTime startA, LocalDateTime endA,
                                   LocalDateTime startB, LocalDateTime endB) {
        return startA.isBefore(endB) && endA.isAfter(startB);
    }

    /**
     * 合并多个时段的峰值并行占用数量（扫描线）。空列表返回 0。
     */
    public static int peakLoad(List<Interval> intervals) {
        return segments(intervals).stream().mapToInt(Segment::load).max().orElse(0);
    }

    /**
     * 计算全部时段并集上的占用分段（按开始时刻排序，相邻同占用分段合并）。
     * 同一时刻的结束事件排在开始事件之前，保证区间右开：[10:00,11:00) 与 [11:00,12:00)
     * 在 11:00 的占用不会被叠加。
     */
    public static List<Segment> segments(List<Interval> intervals) {
        List<Event> events = new ArrayList<>(intervals.size() * 2);
        for (Interval interval : intervals) {
            events.add(new Event(interval.start(), 1));
            events.add(new Event(interval.end(), -1));
        }
        // 同刻先结束（-1）后开始（+1），实现左闭右开。
        events.sort(Comparator.comparing(Event::time).thenComparingInt(Event::delta));

        List<Segment> result = new ArrayList<>();
        int load = 0;
        LocalDateTime segmentStart = null;
        int segmentLoad = 0;
        for (Event event : events) {
            if (segmentStart != null && segmentStart.isBefore(event.time())) {
                if (segmentLoad > 0) {
                    // 与上一段占用相同且首尾相接则合并，保持分段最简且排序稳定。
                    if (!result.isEmpty() && result.get(result.size() - 1).load() == segmentLoad
                            && result.get(result.size() - 1).end().equals(segmentStart)) {
                        Segment last = result.remove(result.size() - 1);
                        result.add(new Segment(last.start(), event.time(), segmentLoad));
                    } else {
                        result.add(new Segment(segmentStart, event.time(), segmentLoad));
                    }
                }
            }
            load += event.delta();
            segmentStart = event.time();
            segmentLoad = load;
        }
        return List.copyOf(result);
    }

    /**
     * 给定时段与窗口 [windowStart, windowEnd) 的重叠部分；不重叠返回 null。
     */
    public static Interval clip(Interval interval, LocalDateTime windowStart,
                                LocalDateTime windowEnd) {
        LocalDateTime start = interval.start().isBefore(windowStart) ? windowStart : interval.start();
        LocalDateTime end = interval.end().isAfter(windowEnd) ? windowEnd : interval.end();
        if (!start.isBefore(end)) {
            return null;
        }
        return new Interval(start, end);
    }

    private record Event(LocalDateTime time, int delta) {
    }
}
