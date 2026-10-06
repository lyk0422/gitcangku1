package com.example.starter.calibration.model;

/**
 * 证书类型：LINEAR=线性（a×读数+b）；HYSTERESIS=迟滞（读数×斜率+按方向选择的偏移）。
 */
public enum CertificateType {
    LINEAR,
    HYSTERESIS
}
