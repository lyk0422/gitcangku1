package com.example.starter.evidence;

/**
 * 阅检副本状态。
 * ACTIVE 已签发（是否有效还取决于 UTC 有效区间，自然到期即视为失效）；
 * REVOKED 已回收（终态，签发历史保留不删除）。
 */
public enum InspectionCopyStatus {
    ACTIVE,
    REVOKED
}