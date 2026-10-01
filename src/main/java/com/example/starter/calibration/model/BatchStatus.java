package com.example.starter.calibration.model;

/**
 * 测量批次状态。
 */
public enum BatchStatus {

    /** 开放：可提交读数与复核，放行后进入已放行。 */
    OPEN,

    /** 已放行：批次结果已放行；新增离群事实须先创建后续修订版本。 */
    RELEASED
}