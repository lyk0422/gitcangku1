package com.example.starter.firmware.domain;

/**
 * 指令状态：PENDING 未决；SUPERSEDED 被迁移原子废弃；SUCCESS 成功；FAILED 失败。
 */
public enum CommandStatus {
    PENDING,
    SUPERSEDED,
    SUCCESS,
    FAILED
}
