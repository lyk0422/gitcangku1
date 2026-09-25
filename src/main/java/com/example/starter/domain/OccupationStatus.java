package com.example.starter.domain;

/** 高度层占用状态。 */
public enum OccupationStatus {
    /** 有效占用，按高度带与时间重叠计入容量。 */
    ACTIVE,
    /** 已取消：立即释放容量，记录作为历史永久保留。 */
    CANCELLED
}
