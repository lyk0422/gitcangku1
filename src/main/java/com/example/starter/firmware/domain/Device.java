package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param currentVersion 设备当前固件版本
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 * @param healthVersion  设备健康版本号，从1开始，人工恢复成功加一
 * @param down           停机/不健康标记：拓扑波次领取置 true，SUCCESS 回执、取消待执行或人工恢复置 false
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     int healthVersion, boolean down) {
}
