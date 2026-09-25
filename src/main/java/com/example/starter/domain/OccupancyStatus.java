package com.example.starter.domain;

/**
 * 高度层占用状态：ACTIVE 占用中消耗容量；CANCELLED 已取消，容量立即释放但历史保留。
 */
public enum OccupancyStatus {
    /** 占用中，参与容量计数。 */
    ACTIVE,
    /** 已取消，不再消耗容量，记录保留不可变。 */
    CANCELLED
}
