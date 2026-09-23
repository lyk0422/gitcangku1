package com.example.starter.blind;

/**
 * 揭盲申请状态。
 */
public enum UnblindStatus {
    /** 待审。 */
    PENDING,
    /** 已由另一名 REVIEWER 批准。 */
    APPROVED,
    /** 申请人（COORDINATOR）主动撤销。 */
    CANCELLED,
    /** 另一名 REVIEWER 填写原因拒绝。 */
    REJECTED,
    /** 到达 expiresAt 后按查询时钟裁决的终态（普通查询不写库）。 */
    EXPIRED
}
