package com.example.starter.calibration.model;

/**
 * 校准证书模式。
 */
public enum CertificateMode {

    /** 线性：y = a×读数 + b。 */
    LINEAR,

    /** 二次：y = a×x² + b×x + c，输入闭区间 [L, U]，导数在区间内严格同号。 */
    QUADRATIC
}
