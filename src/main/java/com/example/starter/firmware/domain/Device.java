package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId            设备唯一标识
 * @param model               设备型号，登记后不可修改
 * @param currentVersion      设备当前固件版本
 * @param bucketNo            灰度分桶号，取值0~99，登记后不可修改
 * @param deviceGroup         设备组，模块豁免按组匹配，登记后不可修改
 * @param moduleDigest        设备模块清单摘要，从未上报为 null
 * @param moduleReportVersion 模块上报版本，每次上报加一，从未上报为 0
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     String deviceGroup, String moduleDigest, int moduleReportVersion) {
}
