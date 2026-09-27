package com.example.starter.firmware.domain;

/**
 * 撤销链任务回执的人工复核状态：
 * PENDING_REVIEW 待人工确认（设备版本停留在来源版本）；
 * CONFIRMED 人工确认完成版本更新；REJECTED 人工拒绝（永不更新）。
 */
public enum ReviewStatus {
    PENDING_REVIEW,
    CONFIRMED,
    REJECTED
}
