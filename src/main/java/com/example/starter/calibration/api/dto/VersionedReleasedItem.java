package com.example.starter.calibration.api.dto;

/**
 * 版本化放行成功响应中的单项。
 *
 * @param measurementKey 业务测量键
 * @param revision       已放行版本号
 */
public record VersionedReleasedItem(String measurementKey, int revision) {
}
