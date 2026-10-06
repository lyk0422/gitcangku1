package com.example.starter.firmware.domain;

/**
 * 组成员任务状态：PENDING 待准备；PREPARED 已暂存（不改设备当前版本）；FAILED 准备失败。
 */
public enum GroupMemberStatus {
    PENDING,
    PREPARED,
    FAILED
}
