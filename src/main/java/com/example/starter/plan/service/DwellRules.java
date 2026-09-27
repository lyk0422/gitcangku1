package com.example.starter.plan.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 站停裁决的纯逻辑：分钟换算与站台占用峰值扫描。不依赖 Spring 与数据库，便于单元测试。
 *
 * <p>区间一律左闭右开：到达瞬间计入占用，发车瞬间不计入；因此同一时刻到达与发车互不冲突。
 */
public final class DwellRules {

    /** 一条待计入峰值扫描的到发区间，时间为 UTC 毫秒。 */
    public record Interval(long startUtc, long endUtc) {
    }

    /**
     * 峰值扫描结果。
     *
     * @param concurrent 扫描区间内的最大并发占用数（实际值，单位个）
     * @param startUtc   最早达到峰值的时刻（含），UTC 毫秒
     * @param endUtc     峰值首次回落的时刻（不含），UTC 毫秒；空输入或峰值持续到最后事件时为 0
     */
    public record Peak(int concurrent, long startUtc, long endUtc) {
    }

    private DwellRules() {
    }

    /**
     * 实际停留分钟数 = (发车-到达)/60000；到发倒置时为负值或零，由调用方按 422 裁决。
     * 调用前须保证时刻对齐整分钟，结果为整数。
     */
    public static int actualDwellMinutes(long arrivalUtcMillis, long departureUtcMillis) {
        return (int) ((departureUtcMillis - arrivalUtcMillis) / 60_000L);
    }

    /**
     * 事件扫描求最大并发。同一时刻先处理发车（-1）再处理到达（+1），体现左闭右开；
     * 同一时刻的整组事件处理完后再读数，因此换车（一发一到）不会出现瞬时负数或叠加。
     * 报告达到最高峰值的最早连续区间 [startUtc, endUtc)，endUtc 为并发首次低于峰值的事件时刻。
     */
    public static Peak peakConcurrent(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return new Peak(0, 0L, 0L);
        }
        List<long[]> events = new ArrayList<>(intervals.size() * 2);
        for (Interval i : intervals) {
            events.add(new long[] {i.startUtc(), 1});
            events.add(new long[] {i.endUtc(), -1});
        }
        // 时刻升序；同时刻 -1（发车）排在 +1（到达）前
        events.sort(Comparator.<long[]>comparingLong(e -> e[0]).thenComparingInt(e -> (int) e[1]));

        int current = 0;
        int peak = 0;
        long peakStart = 0L;
        long peakEnd = 0L;
        boolean windowOpen = false;
        int idx = 0;
        while (idx < events.size()) {
            long time = events.get(idx)[0];
            while (idx < events.size() && events.get(idx)[0] == time) {
                current += (int) events.get(idx)[1];
                idx++;
            }
            if (current > peak) {
                // 进入更高的占用水平：重置峰值区间起点，等待首次回落到新水平之下
                peak = current;
                peakStart = time;
                peakEnd = 0L;
                windowOpen = true;
            } else if (windowOpen && current < peak) {
                peakEnd = time;
                windowOpen = false;
            }
        }
        return new Peak(peak, peakStart, peakEnd);
    }
}
