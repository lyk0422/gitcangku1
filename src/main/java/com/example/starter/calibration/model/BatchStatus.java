package com.example.starter.calibration.model;

/**
 * 测量批次状态。
 */
public enum BatchStatus {

    /** 开放：可提交读数，离群未裁决时阻断放行。 */
    OPEN,

    /** 当前修订已放行：新增读数只能创建后续修订版本（revision+1 并回到 OPEN）。 */
    RELEASED
}