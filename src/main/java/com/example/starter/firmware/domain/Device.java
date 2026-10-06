package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId          设备唯一标识
 * @param model             设备型号，登记后不可修改
 * @param currentVersion    设备当前固件版本
 * @param bucketNo          灰度分桶号，取值0~99，登记后不可修改
 * @param totalSpaceBytes   设备总空间字节数，正整数；null 表示未启用空间档案（旧模式，不做暂存峰值核验）
 * @param currentImageBytes 当前镜像占用字节数，正整数且不大于总空间；null 表示未启用空间档案
 * @param spaceVersion      空间档案版本，从1开始，每次维护与任务首次终结加一
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     Long totalSpaceBytes, Long currentImageBytes, int spaceVersion) {

    /**
     * 是否启用空间档案（登记或维护时写入容量字段）。
     */
    public boolean spaceProfileEnabled() {
        return totalSpaceBytes != null;
    }
}
