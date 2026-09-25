package com.example.starter.evidence;

/**
 * 重量差异复核状态。
 * NONE 无需复核；PENDING 待复核（禁止发起交接与封条核验）；RESOLVED 已复核关闭（不可逆）。
 */
public enum ReviewStatus {
    NONE,
    PENDING,
    RESOLVED
}
