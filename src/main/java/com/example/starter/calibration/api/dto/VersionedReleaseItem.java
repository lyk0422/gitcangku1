package com.example.starter.calibration.api.dto;

/**
 * 版本化放行单项。
 *
 * @param measurementKey 业务测量键
 * @param revision       要放行的确切修订版本号
 */
public record VersionedReleaseItem(String measurementKey, Integer revision) {
}
