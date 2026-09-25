package com.example.starter.domain;

/**
 * 被抢占航线抢占记录的处理状态。
 */
public enum PreemptionItemStatus {
    /** 未处理：航线自被置换后尚未重新提交并获批准；同一航线至多一条。 */
    PENDING,
    /** 已重新提交审查并获得新批件；不自动复原。 */
    RESOLVED
}
