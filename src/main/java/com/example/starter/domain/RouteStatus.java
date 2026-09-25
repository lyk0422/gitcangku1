package com.example.starter.domain;

/** 航线生命周期状态。 */
public enum RouteStatus {
    /** 已创建，尚未通过审查批准。 */
    PENDING,
    /** 审查 CLEAR 已批准，未起飞。 */
    APPROVED,
    /** 被 EMERGENCY 航线抢占置换，可重新提交审查，不自动复原。 */
    DISPLACED,
    /** 已起飞登记，不可被抢占。 */
    DEPARTED
}
