package com.example.starter.calibration.model;

/**
 * 差分记录状态。
 */
public enum DifferentialStatus {

    /** 待审：差分创建后的初始状态，可在放行前取消。 */
    PENDING,

    /** 已放行：两条原测量随差分一并放行；不可再取消。 */
    RELEASED,

    /** 已取消：放行前取消，记录保留不删除，占用随之释放。 */
    CANCELLED
}
