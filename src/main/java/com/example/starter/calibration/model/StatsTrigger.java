package com.example.starter.calibration.model;

/**
 * 批次统计重算触发源。
 */
public enum StatsTrigger {

    /** 读数提交后重算。 */
    READING_SUBMITTED,

    /** REJECT 裁决排除读数后，在同一事务内重算。 */
    VERDICT_REJECT,

    /** 放行时重算并封存进放行快照。 */
    RELEASE
}