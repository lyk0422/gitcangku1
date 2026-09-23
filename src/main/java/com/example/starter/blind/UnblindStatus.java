package com.example.starter.blind;

/**
 * 揭盲申请状态。
 */
public enum UnblindStatus {
    /** 待审（有效，未到期）。 */
    PENDING,
    /** 已由另一名 REVIEWER 批准。 */
    APPROVED,
    /** 申请人本人（COORDINATOR）撤销。 */
    CANCELLED,
    /** 另一名 REVIEWER 填写原因拒绝。 */
    REJECTED,
    /** 到期未决；普通查询按时钟只读展示，归档重申时落库。 */
    EXPIRED
}
