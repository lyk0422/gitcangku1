package com.example.starter.race.domain;

/**
 * 组别重分申请状态：
 * PENDING-待审批；
 * APPROVED-已批准（参赛者组别已在批准事务内迁移）；
 * REJECTED-已拒绝（不改变任何成绩）。
 */
public enum ReclassRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
