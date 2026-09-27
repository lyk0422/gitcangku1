package com.example.starter.firmware.domain;

/**
 * 设备最终信任状态（信任锚 + 是否信任）。
 *
 * @param deviceId          设备ID
 * @param anchorFingerprint 设备信任锚（根证书 SHA-256 指纹，合成值）
 * @param trusted           最终信任状态：true 信任，false 不信任
 */
public record DeviceTrust(String deviceId, String anchorFingerprint, boolean trusted) {
}
