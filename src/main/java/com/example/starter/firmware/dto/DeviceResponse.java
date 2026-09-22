package com.example.starter.firmware.dto;

/**
 * 设备信息响应。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号
 * @param currentVersion 当前固件版本
 * @param bucket         灰度分桶号，0~99
 */
public record DeviceResponse(String deviceId, String model, String currentVersion, int bucket) {
}
