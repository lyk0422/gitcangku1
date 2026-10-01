package com.example.starter.calibration.model;

/**
 * 复核结论。
 */
public enum ReviewDecision {

    /** 接受：保留读数，必须记录理由。 */
    ACCEPT,

    /** 拒绝：在同一事务内排除读数并重算批次统计。 */
    REJECT
}