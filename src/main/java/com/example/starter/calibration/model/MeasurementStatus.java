package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交或后继修订创建后的初始状态。 */
    PENDING,

    /** 已放行：经批量放行或重新放行接口原子放行。证书撤销或批次被复核后仅失去“当前可用”资格。 */
    RELEASED,

    /** 复核驳回：放行批次复核时被驳回，可由原提交人创建单一后继修订。 */
    REJECTED
}
