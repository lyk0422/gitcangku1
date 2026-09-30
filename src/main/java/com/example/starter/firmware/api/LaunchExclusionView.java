package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ProfileExclusion;

/**
 * 投放排除记录视图：不兼容设备、评估时画像版本（无画像为 null）与原因。
 */
public record LaunchExclusionView(String deviceId, Integer profileVersion, String reason) {

    public static LaunchExclusionView of(ProfileExclusion exclusion) {
        return new LaunchExclusionView(exclusion.deviceId(), exclusion.profileVersion(),
                exclusion.reason());
    }
}
