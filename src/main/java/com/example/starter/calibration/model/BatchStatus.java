package com.example.starter.calibration.model;

/**
 * 测量批次状态。
 */
public enum BatchStatus {

    /** 待放行：批次创建后的初始状态；已放行批次新增读数后回到该状态（修订版本递增）。 */
    OPEN,

    /** 已放行：放行快照已封存；新增离群事实只能以后续修订版本创建。 */
    RELEASED
}