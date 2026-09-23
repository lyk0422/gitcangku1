package com.example.starter.domain;

/**
 * UTC 毫秒时间窗口，区间左闭右开 {@code [startMs, endMs)}。
 *
 * <p>起止时刻成对出现：两者均为 {@code null} 表示全时有效；
 * 非全时时 {@code startMs < endMs}。本题只做整数平面坐标与时间窗口的
 * 模拟审查，不估计航点到达时刻，不涉及速度、高度或真实飞行控制。</p>
 *
 * @param startMs 窗口开始时刻，epoch 毫秒（UTC），区间含；{@code null} 表示全时
 * @param endMs   窗口结束时刻，epoch 毫秒（UTC），区间不含；必须严格晚于 startMs
 */
public record TimeWindow(Long startMs, Long endMs) {

    /** 全时有效窗口（缺省语义，旧请求与既有记录按此解释）。 */
    public static final TimeWindow ALL_TIME = new TimeWindow(null, null);

    /** 是否全时有效（起止一对均缺省）。 */
    public boolean allTime() {
        return startMs == null;
    }

    /**
     * 判断两个左闭右开窗口是否存在正长度时间交集。
     *
     * <p>任一方全时则必相交；仅端点相接（一方 end 等于另一方 start）不算相交，
     * 即 {@code [0,10)} 与 {@code [10,20)} 不相交。</p>
     *
     * @param other 另一方窗口
     * @return true 表示两窗口在时间轴上有非空交集
     */
    public boolean intersects(TimeWindow other) {
        if (this.allTime() || other.allTime()) {
            return true;
        }
        return this.startMs < other.endMs && other.startMs < this.endMs;
    }
}
