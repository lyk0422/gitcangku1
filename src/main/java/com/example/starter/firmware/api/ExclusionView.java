package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RolloutExclusion;

/**
 * 硬件不兼容排除视图，供发布人审计：携带排除原因与判定时使用的画像版本。
 */
public record ExclusionView(String deviceId, String reasonCode, Integer profileVersion) {

    public static ExclusionView of(RolloutExclusion exclusion) {
        return new ExclusionView(exclusion.deviceId(), exclusion.reason().name(),
                exclusion.profileVersion());
    }
}
