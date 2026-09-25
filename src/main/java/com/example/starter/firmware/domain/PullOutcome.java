package com.example.starter.firmware.domain;

/**
 * 设备拉取结果：TASK 命中或返回已有任务；EMPTY 通过既有门禁但无投放；
 * INCOMPATIBLE 目标固件不兼容设备硬件型号，不创建任务、不计失败率样本、不改设备状态。
 */
public enum PullOutcome {
    TASK,
    EMPTY,
    INCOMPATIBLE
}
