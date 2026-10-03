package com.example.starter.race.domain;

/**
 * 组别重分申请状态：PENDING-待审批；APPROVED-已批准并完成迁移；REJECTED-已拒绝（不改变成绩）。
 */
public enum ApplicationStatus {
    PENDING,
    APPROVED,
    REJECTED
}