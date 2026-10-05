package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId        设备唯一标识
 * @param model           设备型号，登记后不可修改
 * @param currentVersion  设备当前固件版本（当前启动分区上的版本）
 * @param bucketNo        灰度分桶号，取值0~99，登记后不可修改
 * @param activePartition 当前启动分区：A 或 B；双分区试启动确认后切换，回滚保持不变
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     String activePartition) {
}