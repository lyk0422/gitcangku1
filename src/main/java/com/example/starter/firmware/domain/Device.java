package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId        设备唯一标识
 * @param model           设备型号，登记后不可修改
 * @param currentVersion  设备当前固件版本
 * @param bucketNo        灰度分桶号，取值0~99，登记后不可修改
 * @param securityFloor   安全底线（不可逆安全索引下限），登记时为非负整数，维护与首次成功安装只增不减
 * @param securityVersion 安全档案版本，从1开始，维护与首次成功安装加一
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     int securityFloor, int securityVersion) {
}
