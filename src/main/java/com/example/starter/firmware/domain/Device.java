package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param currentVersion 设备当前固件版本
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 * @param deviceGroup    设备组名，登记后不可修改；为 null 表示不属于任何组，不适用任何模块豁免
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo, String deviceGroup) {
}
