package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Device;

/**
 * 设备视图。availableBytes 为当前余量（总空间-当前镜像-活跃暂存预留），随空间占用实时变化；
 * 旧模式设备的空间字段均为 null。
 */
public record DeviceView(String deviceId, String model, String currentVersion, int bucketNo,
                         Long totalSpaceBytes, Long currentImageBytes, Integer spaceVersion,
                         Long activeReservedBytes, Long availableBytes) {

    public static DeviceView of(Device device, long activeReservedBytes) {
        Long activeReserved = device.spaceTracked() ? activeReservedBytes : null;
        Long available = device.spaceTracked()
                ? device.totalSpaceBytes() - device.currentImageBytes() - activeReservedBytes
                : null;
        return new DeviceView(device.deviceId(), device.model(), device.currentVersion(), device.bucketNo(),
                device.totalSpaceBytes(), device.currentImageBytes(), device.spaceVersion(),
                activeReserved, available);
    }
}
