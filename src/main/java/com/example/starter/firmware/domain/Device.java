package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId        设备唯一标识
 * @param model           设备型号，登记后不可修改
 * @param currentVersion  设备当前固件版本
 * @param currentDigest   当前镜像SHA-256摘要，64位小写十六进制，登记时合成
 * @param imageGeneration 设备镜像代次，从1开始，本地维护更新或增量安装成功加一
 * @param bucketNo        灰度分桶号，取值0~99，登记后不可修改
 */
public record Device(String deviceId, String model, String currentVersion, String currentDigest,
                     int imageGeneration, int bucketNo) {
}