package com.example.starter.coral;

/**
 * 样方查询视图：容量（株）、各状态在册数量与封存快照；未封存时seal为null。
 */
public record PlotView(
        String plotCode,
        PlotStatus status,
        int capacity,
        long activeCount,
        long aliveCount,
        long deadCount,
        long pendingCount,
        SealView seal) {
}