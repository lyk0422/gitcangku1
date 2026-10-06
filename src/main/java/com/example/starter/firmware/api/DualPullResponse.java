package com.example.starter.firmware.api;

/**
 * 设备拉取双分区任务响应：task 为命中或已存在的未终结任务；无匹配投放时为 null。
 */
public record DualPullResponse(DualTaskView task) {
}
