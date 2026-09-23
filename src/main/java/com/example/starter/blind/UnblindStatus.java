package com.example.starter.blind;

/**
 * 揭盲申请状态。
 */
public enum UnblindStatus {
    /** 待审（且未到期）。 */
    PENDING,
    /** 已由另一名 REVIEWER 批准；终态，不追溯设限。 */
    APPROVED,
    /** 申请人本人主动撤销；终态。 */
    CANCELLED,
    /** 另一名 REVIEWER 填写原因拒绝；终态。 */
    REJECTED,
    /** 超过有效期未裁决；普通查询仅按时钟展示，重新申请时在同事务归档落库；终态。 */
    EXPIRED
}
