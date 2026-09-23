package com.example.starter.calibration.model;

/**
 * 标准器版本状态。
 */
public enum StandardVersionStatus {

    /** 有效：可被测量提交绑定。 */
    VALID,

    /** 已失效：被失效单冻结，不得再被新的测量绑定。 */
    INVALID
}
