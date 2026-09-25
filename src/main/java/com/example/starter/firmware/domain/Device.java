package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param hardwareModel  硬件型号，登记后不可修改；登记时未显式提供则等于设备型号
 * @param currentVersion 设备当前固件版本
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 */
public record Device(String deviceId, String model, String hardwareModel, String currentVersion, int bucketNo) {
}
