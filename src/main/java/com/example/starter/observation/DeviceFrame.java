package com.example.starter.observation;

/**
 * 设备坐标基准登记：对应 device_frame 表的一行。
 *
 * @param deviceId     设备唯一标识
 * @param frameVersion 设备当前登记的坐标基准版本
 */
public record DeviceFrame(String deviceId, String frameVersion) {
}
