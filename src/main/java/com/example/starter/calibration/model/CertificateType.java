package com.example.starter.calibration.model;

/**
 * 校准证书类型。LINEAR=线性（a×读数+b）；CURVE=分段线性曲线（节点间线性插值，禁止外推）。
 */
public enum CertificateType {
    LINEAR,
    CURVE
}
