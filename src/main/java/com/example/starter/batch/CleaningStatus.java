package com.example.starter.batch;

/**
 * 清洗记录状态机：PENDING 待确认；CONFIRMED 已由另一名 QUALITY 确认且残留不超过 0.1 毫克；
 * REJECTED 确认时残留超限（按未舍入量判断）判为不合格，终态，不可改写为合格。
 */
public enum CleaningStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}