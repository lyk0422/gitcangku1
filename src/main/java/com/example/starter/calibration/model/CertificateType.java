package com.example.starter.calibration.model;

/**
 * 校准证书类型。
 */
public enum CertificateType {

    /** 线性证书：校正值 = a×读数 + b。 */
    LINEAR,

    /** 曲线证书：3～8 个节点，节点闭区间内按相邻节点线性插值，禁止外推。 */
    CURVE
}
