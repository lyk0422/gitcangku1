package com.example.starter.calibration.model;

/**
 * 期间核查判定。
 */
public enum CheckVerdict {

    /** 通过：|标准值-实测值| 不大于容差。 */
    PASS,

    /** 失败：|标准值-实测值| 大于容差，触发追溯隔离。 */
    FAIL
}
