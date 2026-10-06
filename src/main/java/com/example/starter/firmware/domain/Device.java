package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param currentVersion 设备当前固件版本
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 * @param healthStatus   健康状态：HEALTHY 健康，DOWN 停机升级中，UNHEALTHY 升级失败待人工恢复
 * @param healthVersion  健康版本号，从1开始，人工恢复成功加一
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     HealthStatus healthStatus, int healthVersion) {

    /**
     * 新登记设备：健康、健康版本为1。
     */
    public static Device registered(String deviceId, String model, String currentVersion, int bucketNo) {
        return new Device(deviceId, model, currentVersion, bucketNo, HealthStatus.HEALTHY, 1);
    }
}
