package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param currentVersion 设备当前固件版本
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 * @param bootCount      启动计数，不可递减的非负值；登记为0，成功回执或计数维护后更新
 * @param profileVersion 设备档案版本，从1开始，计数维护成功后加一
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     long bootCount, int profileVersion) {
}
