package com.example.starter.model;

/**
 * 航段状态：REQUESTED 已申请待指派；ASSIGNED 已指派未登轮；BOARDED 已登轮未离轮；
 * COMPLETED 已确认离轮；CANCELLED 船舶取消（保留已发生的登轮/离轮事实）。
 */
public enum SegmentStatus {
    REQUESTED,
    ASSIGNED,
    BOARDED,
    COMPLETED,
    CANCELLED
}