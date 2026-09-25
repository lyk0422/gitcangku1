package com.example.starter.observation;

/**
 * 复核结论。
 */
public enum ReviewConclusion {

    /** 确认质量问题：该版本该类别首个有效确认扣减置信度 20。 */
    CONFIRMED,

    /** 驳回：不影响置信度。 */
    DISMISSED
}
