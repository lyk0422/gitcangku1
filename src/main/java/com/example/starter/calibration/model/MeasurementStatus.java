package com.example.starter.calibration.model;

/**
 * 测量记录状态。
 */
public enum MeasurementStatus {

    /** 待放行：测量提交后的初始状态，等待同行复核与放行。 */
    PENDING,

    /** 待修订：收到同行复核 RETURN 后所处状态，禁止放行，修订后产生新版本。 */
    RETURNED,

    /** 已放行：经放行接口原子放行。证书撤销后状态保留，仅失去“当前可用”资格。 */
    RELEASED,

    /** 已被新版本取代：修订后旧版本保留为历史，不再是当前版本。 */
    SUPERSEDED
}
