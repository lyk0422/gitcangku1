package com.example.starter.calibration.model;

/**
 * 离群复核结论。
 */
public enum ReviewDecision {

    /** 保留读数：记录理由，读数继续参与批次统计。 */
    ACCEPT,

    /** 排除读数：在裁决形成的同一事务内排除读数并重算批次统计。 */
    REJECT
}
