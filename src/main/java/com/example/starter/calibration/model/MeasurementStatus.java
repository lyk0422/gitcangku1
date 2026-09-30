package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交后的初始状态。 */
    PENDING,

    /** 已放行：经批量放行接口原子放行。证书撤销后状态保留，仅失去“当前可用”资格。 */
    RELEASED,

    /** 已排除：读数离群且经两名复核人同议 REJECT，在裁决事务内排除，不参与批次统计与放行。 */
    EXCLUDED
}
