package com.example.starter.firmware.domain;

/**
 * 组成员任务状态：PENDING 待准备；PREPARED 已暂存（未切版本）；FAILED 准备失败；
 * CANCELLED 已取消；ACTIVATED 已激活。
 */
public enum GroupMemberStatus {
    PENDING,
    PREPARED,
    FAILED,
    CANCELLED,
    ACTIVATED
}
