package com.example.starter.calibration.model;

/**
 * 同行复核结论。
 */
public enum ReviewConclusion {

    /** 通过：使被复核版本满足放行门禁的复核条件。 */
    PASS,

    /** 退回：使测量转回待修订状态且禁止放行。 */
    RETURN
}
