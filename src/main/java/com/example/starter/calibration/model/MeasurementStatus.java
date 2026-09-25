package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交后的初始状态；驳回后经重算修订也回到本状态。 */
    PENDING,

    /** 已放行：经批量放行接口原子放行。证书撤销后状态保留，仅失去“当前可用”资格。 */
    RELEASED,

    /** 已驳回：未放行测量被驳回，可在修订环境/不确定度后提交重算回到待放行。 */
    REJECTED
}
