package com.example.starter.observation;

/**
 * 质量标记状态。
 */
public enum FlagStatus {
    /** 待复核 */
    PENDING_REVIEW,
    /** 已确认（复核结论 CONFIRMED） */
    CONFIRMED,
    /** 已驳回（复核结论 DISMISSED） */
    DISMISSED,
    /** 已过期：观测产生新版本，不再可复核，也不影响新版本 */
    STALE
}
