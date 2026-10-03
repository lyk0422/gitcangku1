package com.example.starter.calibration.model;

/**
 * 复核结论。
 */
public enum ReviewDecision {

    /** 保留读数，必须记录理由。 */
    ACCEPT,

    /** 排除读数，裁决生效时在同一事务内排除该读数并重算批次统计。 */
    REJECT
}