package com.example.starter.evidence;

/**
 * 保留冻结层级。CASE 案件级冻结覆盖案件下全部证物；EVIDENCE 仅冻结单件证物。
 */
public enum HoldScope {
    CASE,
    EVIDENCE
}
