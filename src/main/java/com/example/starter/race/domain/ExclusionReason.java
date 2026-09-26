package com.example.starter.race.domain;

/**
 * 被排除计时的原因常量；写入 checkpoint_timing 与封榜快照明细后不可改写。
 */
public final class ExclusionReason {

    /** 医疗暂停期间提交的分段被排除，不参与排名。 */
    public static final String MEDICAL_HOLD = "MEDICAL_HOLD";

    private ExclusionReason() {
    }
}
