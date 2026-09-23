package com.example.starter.firmware.domain;

/**
 * 下发指令状态：PENDING 未决；SUPERSEDED 被迁移废弃；SETTLED 已按回执结算。
 */
public enum CommandStatus {
    PENDING,
    SUPERSEDED,
    SETTLED
}
