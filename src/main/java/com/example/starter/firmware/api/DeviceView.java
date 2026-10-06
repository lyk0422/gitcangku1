package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。空间档案字段（totalSpaceBytes/currentImageBytes）为 null 表示旧模式；
 * reservedBytes 为当前活跃暂存预留（压缩包+新镜像），availableBytes 为当前余量。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         Long totalSpaceBytes, Long currentImageBytes, int spaceVersion,
                         Long reservedBytes, Long availableBytes) {

    public static DeviceView of(Device device, long reservedBytes) {
        Long available = device.spaceProfileEnabled()
                ? device.totalSpaceBytes() - device.currentImageBytes() - reservedBytes
                : null;
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(),
                device.bucketNo(), device.totalSpaceBytes(), device.currentImageBytes(),
                device.spaceVersion(), device.spaceProfileEnabled() ? reservedBytes : null, available);
    }
}
