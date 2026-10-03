package com.example.starter.race.domain;

/**
 * 组别重分申请状态：PENDING-待审批；APPROVED-已批准（选手已迁移）；REJECTED-已拒绝（成绩不变）。
 */
public enum ReclassRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}