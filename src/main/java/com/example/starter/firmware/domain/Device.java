package com.example.starter.firmware.domain;

/**
 * 设备登记信息。
 *
 * @param deviceId          设备唯一标识
 * @param model             设备型号，登记后不可修改
 * @param currentVersion    设备当前固件版本
 * @param bucketNo          灰度分桶号，取值0~99，登记后不可修改
 * @param totalSpaceBytes   总空间字节数，正整数；NULL 为旧模式，不做暂存空间核验
 * @param currentImageBytes 当前镜像字节数，不超过总空间；NULL 为旧模式
 * @param spaceVersion      空间档案版本，从1开始，维护与首次任务终结加一；NULL 为旧模式
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo,
                     Long totalSpaceBytes, Long currentImageBytes, Integer spaceVersion) {

    /**
     * 是否登记了空间档案（版本1）。旧设备无容量字段，保留原模式。
     */
    public boolean spaceTracked() {
        return totalSpaceBytes != null;
    }
}
