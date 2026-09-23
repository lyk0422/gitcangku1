package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交后的初始状态。 */
    PENDING,

    /** 已放行：经批量放行接口原子放行。证书撤销后状态保留，仅失去“当前可用”资格。 */
    RELEASED,

    /** 失效冻结未审核：失效单激活时仍处于待放行，被标记为阻塞，不得再放行。 */
    BLOCKED,

    /** 失效冻结待复审：失效单激活时已放行，状态改为待复审；原放行快照与数值保留。 */
    REVIEW_REQUIRED
}
