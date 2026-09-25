package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交后的初始状态。 */
    PENDING,

    /** 待修订：收到 RETURN 复核后进入，禁止放行；修订后回到 PENDING 并递增版本。 */
    NEEDS_REVISION,

    /** 已放行：经批量放行接口原子放行。证书撤销后状态保留，仅失去“当前可用”资格。 */
    RELEASED
}
