package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param currentVersion 设备当前固件版本
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 * @param bootCount      设备启动计数，不可递减的非负整数，登记时为0；维护或启动挑战成功回执后提升
 * @param deviceVersion  设备档案版本，从1开始，每次维护（提交expectedDeviceVersion校验通过）成功加一
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     long bootCount, int deviceVersion) {
}
