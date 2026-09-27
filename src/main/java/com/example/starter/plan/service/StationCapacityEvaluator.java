package com.example.starter.plan.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站台容量与停站时长的纯逻辑评估器（不访问数据库，时间均为 UTC，区间左闭右开）。
 *
 * <p>容量扫描：同一站点把所有到发区间展开为开始 +1 / 结束 -1 事件，
 * 同一时刻先处理结束再处理开始（[a,b) 与 [b,c) 相邻不算并发），
 * 开始事件按 (scheduleKey, seq) 稳定排序；任一开始使在站列车数超过容量即记一条超卖明细，
 * 明细携带实际并发数、容量与超出数量。
 */
public final class StationCapacityEvaluator {

    private StationCapacityEvaluator() {
    }

    /**
     * 一条参与容量统计的站台占用区间。
     *
     * @param stationId   站点业务 ID
     * @param scheduleKey 所属计划业务键
     * @param trainNo     列车编号
     * @param seq         站停序号
     * @param start       占用开始（到达，含），UTC
     * @param end         占用结束（发车，不含），UTC
     */
    public record PlatformInterval(String stationId, String scheduleKey, String trainNo, int seq,
                                   Instant start, Instant end) {
    }

    /**
     * 容量超卖明细。
     *
     * @param scheduleKey 导致超限的开始事件所属计划
     * @param trainNo     列车编号
     * @param seq         站停序号
     * @param time        超限发生时刻（该区间的开始时刻），UTC
     * @param concurrent  该开始计入后的实际同时在站列车数（实际值）
     * @param capacity    站台容量（要求值）
     * @param overflow    超出数量 = concurrent - capacity（差额，最小为 1）
     */
    public record CapacityViolation(String scheduleKey, String trainNo, int seq, Instant time,
                                    int concurrent, int capacity, int overflow) {
    }

    /**
     * 单站评估结果。
     *
     * @param peakConcurrent 峰值同时在站列车数（实际值）
     * @param violations     按扫描顺序产生的超卖明细
     */
    public record StationResult(int peakConcurrent, List<CapacityViolation> violations) {
    }

    /**
     * 按站点分组评估，结果保持站点首次出现顺序。
     *
     * @param intervals  全部站台占用区间
     * @param capacities 各站点容量（要求值），缺失站点不限制
     */
    public static Map<String, StationResult> evaluate(List<PlatformInterval> intervals,
                                                      Map<String, Integer> capacities) {
        Map<String, List<PlatformInterval>> byStation = new LinkedHashMap<>();
        for (PlatformInterval interval : intervals) {
            byStation.computeIfAbsent(interval.stationId(), k -> new ArrayList<>()).add(interval);
        }
        Map<String, StationResult> results = new LinkedHashMap<>();
        byStation.forEach((stationId, list) -> {
            int capacity = capacities.getOrDefault(stationId, Integer.MAX_VALUE);
            List<Event> events = new ArrayList<>(list.size() * 2);
            for (PlatformInterval v : list) {
                events.add(new Event(v.start(), 1, v));
                events.add(new Event(v.end(), -1, v));
            }
            results.put(stationId, scan(events, capacity));
        });
        return results;
    }

    private record Event(Instant time, int delta, PlatformInterval interval) {
    }

    private static StationResult scan(List<Event> events, int capacity) {
        // 时刻升序；同一时刻结束(-1)先于开始(+1)，体现左闭右开；
        // 同为开始时按 (scheduleKey, seq) 排序，保证并发裁决与明细顺序稳定。
        events.sort(Comparator.comparing(Event::time)
                .thenComparingInt(Event::delta)
                .thenComparing(e -> e.interval().scheduleKey())
                .thenComparingInt(e -> e.interval().seq()));
        int running = 0;
        int peak = 0;
        List<CapacityViolation> violations = new ArrayList<>();
        for (Event event : events) {
            running += event.delta();
            if (running > peak) {
                peak = running;
            }
            if (event.delta() == 1 && running > capacity) {
                PlatformInterval v = event.interval();
                violations.add(new CapacityViolation(v.scheduleKey(), v.trainNo(), v.seq(),
                        event.time(), running, capacity, running - capacity));
            }
        }
        return new StationResult(peak, List.copyOf(violations));
    }

    /**
     * 停站时长（分钟，向下取整）；两端齐全且发车晚于到达时调用。
     */
    public static long dwellMinutes(Instant arrival, Instant departure) {
        return Duration.between(arrival, departure).toMinutes();
    }
}
