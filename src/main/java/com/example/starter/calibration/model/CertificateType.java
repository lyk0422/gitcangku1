package com.example.starter.calibration.model;

/**
 * 校准证书类型。
 */
public enum CertificateType {

    /** 线性证书：y = a·x + b。 */
    LINEAR,

    /** 二次证书：y = a·x² + b·x + c，声明输入闭区间 [L, U]，导数在区间内严格同号。 */
    QUADRATIC
}
