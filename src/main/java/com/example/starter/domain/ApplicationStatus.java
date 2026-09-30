package com.example.starter.domain;

/**
 * 靠泊申请状态。
 */
public enum ApplicationStatus {

    /** 已确认靠泊，占用泊位区间。 */
    CONFIRMED,
    /** 潮高修订后需人工复核，不占用泊位。 */
    PENDING_REVIEW,
    /** 校核或复核拒绝，不占用泊位。 */
    REJECTED
}
