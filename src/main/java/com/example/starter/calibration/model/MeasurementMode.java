package com.example.starter.calibration.model;

/**
 * 测量模式：STANDARD=普通测量（仅用证书）；DRIFT_REF=参考漂移测量（证书值减两端档案线性插值偏移）。
 */
public enum MeasurementMode {
    /** 普通测量。 */
    STANDARD,
    /** 参考漂移测量。 */
    DRIFT_REF
}
