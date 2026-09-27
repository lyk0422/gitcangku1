package com.example.starter.firmware.api;

/**
 * 批量启动中单个设备的拒绝原因。
 */
public record Rejection(String deviceId, String code, String message) {
}
