package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param currentVersion 设备当前固件版本，仅成功回执更新
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 * @param groupName      设备组，登记后不可修改；null 表示未分组，模块豁免按设备组匹配
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo, String groupName) {
}
