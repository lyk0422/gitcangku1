package com.example.starter.observation;

/**
 * 质量标记状态。
 */
public enum FlagStatus {

    /** 待复核。 */
    PENDING,

    /** 复核确认：同一版本下该类别首个有效 CONFIRMED 会扣减置信度。 */
    CONFIRMED,

    /** 复核驳回：不影响置信度。 */
    DISMISSED,

    /** 观测已产生新版本，标记失效：不可再复核，也不影响新版本。 */
    STALE
}
