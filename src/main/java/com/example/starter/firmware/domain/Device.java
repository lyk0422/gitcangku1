package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId        设备唯一标识
 * @param model           设备型号，登记后不可修改
 * @param currentVersion  设备当前固件版本
 * @param bucketNo        灰度分桶号，取值0~99，登记后不可修改
 * @param activePartition 当前启动分区：A 或 B，仅双分区任务健康确认提交后切换
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     String activePartition) {

    /**
     * 兼容旧调用方：未指定分区时默认当前启动分区为 A。
     */
    public Device(String deviceId, String model, String currentVersion, int bucketNo) {
        this(deviceId, model, currentVersion, bucketNo, "A");
    }
}