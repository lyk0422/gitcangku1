package com.example.starter.firmware.api;

/**
 * 设备拉取响应：task 为命中或已存在的任务；无匹配投放时为 null。
 */
public record PullResponse(TaskView task) {
}
