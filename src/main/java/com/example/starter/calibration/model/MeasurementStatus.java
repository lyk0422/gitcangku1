package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交后的初始状态。 */
    PENDING,

    /** 已放行：经批量放行接口原子放行。证书撤销后状态保留，仅失去“当前可用”资格。 */
    RELEASED,

    /** 失效冻结阻断：未审核记录命中标准器失效闭包后被冻结，不得再放行。 */
    BLOCKED,

    /** 失效后需复审：已放行结果命中失效闭包，保留原放行快照与数值，等待复审。 */
    REVIEW_REQUIRED
}
