package com.example.starter.calibration.model;

/**
 * 期间核查判定结果。
 */
public enum CheckResult {

    /** 核查通过：|实测值 - 标准值| &lt;= 容差。 */
    PASS,

    /** 核查失败：|实测值 - 标准值| &gt; 容差，触发追溯区间隔离。 */
    FAIL
}
