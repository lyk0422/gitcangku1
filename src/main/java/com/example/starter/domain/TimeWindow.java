package com.example.starter.domain;

/**
 * 可选的 UTC 毫秒半开时间窗口 [startUtcMillis, endUtcMillis)。
 *
 * <p>窗口仅用于判断“航线整体飞行窗口”与“区域有效窗口”是否相交：
 * 区间左闭右开，仅端点相接（前一窗口终点恰为后一窗口起点）不算相交；
 * 任一方全时有效（null）时时间必然相交。</p>
 *
 * <p>本题不估计每个航点的到达时刻，整条航线统一使用一个窗口；
 * 不引入速度、高度或真实飞行控制。</p>
 *
 * @param startUtcMillis 窗口起点（含），UTC 毫秒
 * @param endUtcMillis   窗口终点（不含），UTC 毫秒，严格大于起点
 */
public record TimeWindow(long startUtcMillis, long endUtcMillis) {

    /**
     * 判断两个半开窗口是否相交；任一方为 null（全时有效）则必相交。
     *
     * @param a 窗口 A，null 表示全时
     * @param b 窗口 B，null 表示全时
     * @return true 表示存在长度大于 0 的时间交集
     */
    public static boolean intersects(TimeWindow a, TimeWindow b) {
        if (a == null || b == null) {
            return true;
        }
        // 左闭右开：交集为 [max(起点), min(终点))，仅当 max 起点严格小于 min 终点
        long lo = Math.max(a.startUtcMillis, b.startUtcMillis);
        long hi = Math.min(a.endUtcMillis, b.endUtcMillis);
        return lo < hi;
    }
}
