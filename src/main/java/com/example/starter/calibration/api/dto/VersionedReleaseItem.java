package com.example.starter.calibration.api.dto;

/**
 * 版本化放行单项：指定测量键与必须精确匹配的版本号。
 *
 * @param measurementKey 业务测量键
 * @param revision       要求放行的具体版本号；必须正是该键最新 PENDING 版本
 */
public record VersionedReleaseItem(String measurementKey, Integer revision) {
}
