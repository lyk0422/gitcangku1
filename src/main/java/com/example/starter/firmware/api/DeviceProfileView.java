package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeviceProfile;

import java.util.List;

/**
 * 设备硬件画像视图。model、memoryMb、tags 为 null 表示设备缺少该画像字段。
 */
public record DeviceProfileView(String deviceId, String model, Integer memoryMb, List<String> tags,
                                int profileVersion) {

    public static DeviceProfileView of(DeviceProfile profile) {
        return new DeviceProfileView(profile.deviceId(), profile.model(), profile.memoryMb(),
                profile.tags() == null ? null : List.copyOf(profile.tags()), profile.profileVersion());
    }
}
