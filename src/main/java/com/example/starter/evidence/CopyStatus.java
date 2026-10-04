package com.example.starter.evidence;

/**
 * 阅检副本状态。
 * ACTIVE 未回收；REVOKED 已回收（不可再变，回收不删除签发历史）。
 * 自然到期不修改状态：超过 valid_until 即视为失效，与 REVOKED 一样不可读取。
 */
public enum CopyStatus {
    ACTIVE,
    REVOKED
}