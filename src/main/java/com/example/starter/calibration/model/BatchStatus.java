package com.example.starter.calibration.model;

/**
 * 测量批次状态。
 */
public enum BatchStatus {

    /** 未放行：批次刚创建、存在待裁决离群，或已放行修订之后又创建了后续修订。 */
    OPEN,

    /** 当前修订已放行；该修订的读数集合与放行快照冻结，只允许新增后续修订。 */
    RELEASED
}
