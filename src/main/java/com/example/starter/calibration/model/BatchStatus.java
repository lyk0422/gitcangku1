package com.example.starter.calibration.model;

/**
 * 放行批次状态。
 */
public enum BatchStatus {

    /** 生效中：批次内测量当前对外可用（证书未撤销前提下）。 */
    RELEASED,

    /** 复核驳回待处理：批次内测量保持内容但不再对外可用，等待修订与重新放行。 */
    REVIEW_REQUIRED,

    /** 已被重新放行取代：终态不可变，旧批次与旧结果仅作历史保留。 */
    SUPERSEDED
}
