package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId        设备唯一标识
 * @param model           设备型号，登记后不可修改
 * @param currentVersion  设备当前固件版本
 * @param bucketNo        灰度分桶号，取值0~99，登记后不可修改
 * @param activePartition 当前启动分区（A或B）；双分区试启动提交后切换，回滚保持原分区
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     String activePartition) {

    /**
     * 兼容旧调用方：未登记分区时默认当前分区为 A。
     */
    public Device(String deviceId, String model, String currentVersion, int bucketNo) {
        this(deviceId, model, currentVersion, bucketNo, "A");
    }

    /**
     * 试启动目标分区：当前分区的另一侧。
     */
    public String inactivePartition() {
        return "A".equals(activePartition) ? "B" : "A";
    }
}
