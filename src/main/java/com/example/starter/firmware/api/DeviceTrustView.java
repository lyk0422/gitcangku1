package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeviceTrust;

/**
 * 设备最终信任状态视图。
 */
public record DeviceTrustView(String deviceId, String anchorFingerprint, boolean trusted) {

    public static DeviceTrustView of(DeviceTrust trust) {
        return new DeviceTrustView(trust.deviceId(), trust.anchorFingerprint(), trust.trusted());
    }
}
