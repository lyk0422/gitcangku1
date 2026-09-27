package com.example.starter.firmware.domain;

/**
 * 设备模块上报历史（只增不改）。
 *
 * @param reportVersion 上报版本，从1开始
 * @param moduleDigest  本次上报模块摘要
 * @param reportedAtUtc 上报时刻，UTC，ISO-8601
 */
public record DeviceModuleReport(int reportVersion, String moduleDigest, String reportedAtUtc) {
}
