package com.example.starter.firmware.api;

/**
 * 设备启动分区视图。
 */
public record BootStateView(String deviceId, String currentPartition) {
}
