package com.example.starter.calibration.model;

/**
 * 测量模式。
 */
public enum MeasurementMode {

    /** 普通测量：仅按测量时刻匹配有效证书，计算 a×读数+b。 */
    STANDARD,

    /** 参考漂移测量：引用两份未撤销漂移档案夹住测量时刻，按线性时间插值校正。 */
    DRIFT
}
