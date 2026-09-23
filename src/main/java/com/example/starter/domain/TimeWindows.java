package com.example.starter.domain;

/**
 * UTC 毫秒时间窗口判定。窗口为左闭右开区间 [start, end)，
 * 起止成对为 null 表示全时有效。仅端点相接不算相交。
 * 本题只模拟时间窗口重叠，不涉及真实飞行控制。
 */
public final class TimeWindows {

    private TimeWindows() {
    }

    /**
     * 判断两个左闭右开窗口是否存在共同时刻。
     * 任一方全时（起始为 null，此时结束必同为 null）则必相交；
     * 仅端点相接（如 [100,200) 与 [200,300)）不相交。
     *
     * @param start1 窗口 1 起始（UTC epoch 毫秒，左闭），null 表示全时
     * @param end1   窗口 1 结束（UTC epoch 毫秒，右开）
     * @param start2 窗口 2 起始（UTC epoch 毫秒，左闭），null 表示全时
     * @param end2   窗口 2 结束（UTC epoch 毫秒，右开）
     * @return true 表示存在共同时刻
     */
    public static boolean overlaps(Long start1, Long end1, Long start2, Long end2) {
        if (start1 == null || start2 == null) {
            return true;
        }
        return start1 < end2 && start2 < end1;
    }
}
