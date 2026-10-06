package com.example.starter.calibration.model;

/**
 * 校准证书类型。
 */
public enum CertType {

    /** 线性证书：校正值 = a×读数 + b，走原单测量提交流程。 */
    LINEAR,

    /** 迟滞证书：校正值 = 斜率×读数 + 方向偏移（上升/下降），走校准会话流程。 */
    HYSTERESIS
}
