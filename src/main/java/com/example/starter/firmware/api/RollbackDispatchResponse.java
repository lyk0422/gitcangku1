package com.example.starter.firmware.api;

/**
 * 设备回退派发响应：hopTask 为命中或已存在的回跳任务；无可派发跳次时为 null。
 */
public record RollbackDispatchResponse(RollbackHopTaskView hopTask) {
}
