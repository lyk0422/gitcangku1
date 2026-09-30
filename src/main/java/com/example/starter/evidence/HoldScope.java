package com.example.starter.evidence;

/**
 * 保留冻结级别。
 * CASE 案件级冻结（作用于案件下全部证物）；EVIDENCE 单件证物级冻结（仅作用于该证物）。
 */
public enum HoldScope {
    CASE,
    EVIDENCE
}
