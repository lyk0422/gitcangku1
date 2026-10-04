package com.example.starter.race.domain;

/**
 * 执行凭证状态：PENDING-已登记待确认；CONFIRMED-已由另一名裁判确认并产生抵扣。
 */
public enum ExecutionStatus {
    PENDING,
    CONFIRMED
}