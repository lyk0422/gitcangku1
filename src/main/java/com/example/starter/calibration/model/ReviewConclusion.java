package com.example.starter.calibration.model;

/**
 * 同行复核结论。
 */
public enum ReviewConclusion {

    /** 复核通过：使被复核版本满足放行前的同行复核门禁。 */
    PASS,

    /** 退回修订：测量转回待修订状态，禁止放行。 */
    RETURN
}
