package com.example.starter.firmware.api;

/**
 * 设备标签批量修改响应：返回变更后的实际字典版本、实际涉及设备数与变更时刻（UTC）。
 */
public record DeviceTagBatchResponse(long dictVersion, int deviceCount, String changedAtUtc) {
}
