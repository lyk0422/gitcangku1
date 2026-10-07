package com.example.starter.firmware.domain;

/**
 * 投放任务类型：NORMAL 普通任务（原流程）；BOOT_CHALLENGE 启动挑战任务，
 * 领取时冻结设备启动计数基线并生成唯一挑战串，回执须携带匹配挑战与更大的启动计数。
 */
public enum TaskType {
    NORMAL,
    BOOT_CHALLENGE
}
