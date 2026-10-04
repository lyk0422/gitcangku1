package com.example.starter.race.domain;

/**
 * 绕圈赛事每圈依次经过的点：A、B、FINISH。
 * order 用于把（圈号, 点）展开成全赛程严格递增序列。
 */
public enum LapPoint {
    A(1),
    B(2),
    FINISH(3);

    /** 每圈点数。 */
    public static final int POINTS_PER_LAP = 3;

    private final int order;

    LapPoint(int order) {
        this.order = order;
    }

    /** 圈内顺序：A=1，B=2，FINISH=3。 */
    public int order() {
        return order;
    }

    /**
     * 全赛程序列位置：第 lapNo 圈（从1开始）的该点在整个赛程中的序号，从1连续递增。
     */
    public static int sequence(int lapNo, LapPoint point) {
        return (lapNo - 1) * POINTS_PER_LAP + point.order;
    }
}