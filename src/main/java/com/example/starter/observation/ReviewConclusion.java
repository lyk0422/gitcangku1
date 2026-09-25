package com.example.starter.observation;

/**
 * 复核结论：CONFIRMED 确认（扣减置信度）/ DISMISSED 驳回（不影响置信度）。
 */
public enum ReviewConclusion {
    /** 确认质量问题成立 */
    CONFIRMED,
    /** 驳回质量问题 */
    DISMISSED
}
