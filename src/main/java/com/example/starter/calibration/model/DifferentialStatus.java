package com.example.starter.calibration.model;

/**
 * 差分记录状态。
 */
public enum DifferentialStatus {

    /** 待审：差分创建后的初始状态，可放行或取消。 */
    PENDING,

    /** 已放行：差分放行同时放行两条原测量并冻结双方证书与精确值；不可再取消。 */
    RELEASED,

    /** 已取消：放行前取消，记录保留不删除，配对占用随之释放。 */
    CANCELLED
}
