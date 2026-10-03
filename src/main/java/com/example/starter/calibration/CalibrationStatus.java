package com.example.starter.calibration;

/**
 * 校准记录状态。
 */
public enum CalibrationStatus {
    /** 有效，可用于绑定。 */
    ACTIVE,
    /** 已被复核作废，不可再用于绑定。 */
    INVALIDATED
}