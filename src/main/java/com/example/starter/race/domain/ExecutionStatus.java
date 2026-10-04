package com.example.starter.race.domain;

/**
 * 罚时执行凭证状态：PENDING-待确认，CONFIRMED-已确认（抵扣值已冻结）。
 */
public enum ExecutionStatus {
    PENDING,
    CONFIRMED
}