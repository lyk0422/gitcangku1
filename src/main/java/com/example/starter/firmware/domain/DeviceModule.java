package com.example.starter.firmware.domain;

/**
 * 设备上报的模块清单条目。
 *
 * @param deviceId      设备ID
 * @param moduleName    模块名
 * @param version       模块版本，点分数字
 * @param digest        模块版本摘要（构建/哈希摘要），原样固化到任务与判定证据
 * @param reportedAtUtc 本次上报时刻，UTC，ISO-8601；同设备后提交的上报整体覆盖此前清单
 */
public record DeviceModule(String deviceId, String moduleName, String version, String digest,
                           String reportedAtUtc) {
}
