package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ProfileSnapshot;

import java.util.List;

/**
 * 设备硬件画像当前版本视图。
 */
public record ProfileView(String deviceId, int version, String model, Integer memoryMb, List<String> tags) {

    public static ProfileView of(ProfileSnapshot profile) {
        return new ProfileView(profile.deviceId(), profile.version(), profile.model(),
                profile.memoryMb(), profile.tags());
    }
}
