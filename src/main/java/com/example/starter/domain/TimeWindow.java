package com.example.starter.domain;

/**
 * 可选的有效时间窗口，UTC 毫秒时刻，区间左闭右开 {@code [startUtcMillis, endUtcMillis)}。
 *
 * <p>起止时刻必须成对出现且开始严格早于结束；起止均为 {@code null} 表示全时有效。
 * 本类型不涉及速度、高度或真实飞行控制：整条航线统一使用同一个窗口，
 * 不估计每个航点的到达时刻。</p>
 *
 * @param startUtcMillis 窗口起点（含），UTC 毫秒；与 endUtcMillis 同时为 null 表示全时
 * @param endUtcMillis   窗口终点（不含），UTC 毫秒；必须严格晚于起点
 */
public record TimeWindow(Long startUtcMillis, Long endUtcMillis) {

    /** 全时有效窗口。 */
    public static final TimeWindow ALWAYS = new TimeWindow(null, null);

    /** 是否全时有效。 */
    public boolean isAlways() {
        return startUtcMillis == null && endUtcMillis == null;
    }

    /**
     * 判断两个左闭右开窗口是否有正长度的时间交集。
     *
     * <p>任一方全时则必相交；时间仅在端点相接（一方起点等于另一方终点）不相交。</p>
     *
     * @param a 第一个窗口（null 按全时解释，兼容缺少窗口的既有记录）
     * @param b 第二个窗口（null 按全时解释）
     * @return true 表示两窗口存在非空时间交集
     */
    public static boolean overlaps(TimeWindow a, TimeWindow b) {
        if (a == null || a.isAlways() || b == null || b.isAlways()) {
            return true;
        }
        return a.startUtcMillis < b.endUtcMillis && b.startUtcMillis < a.endUtcMillis;
    }
}
