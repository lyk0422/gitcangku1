package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交或重算后的初始状态。 */
    PENDING,

    /** 已放行：经批量放行接口原子放行。证书撤销后状态保留，仅失去“当前可用”资格。 */
    RELEASED,

    /** 已驳回：经驳回接口驳回修订；可通过重算生成新的待放行版本。 */
    REJECTED
}
