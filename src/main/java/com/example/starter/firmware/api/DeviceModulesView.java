package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备模块清单视图（按模块名排序）。
 *
 * @param deviceId      设备ID
 * @param reportVersion 上报版本号，首次为 1
 * @param reportedAtUtc 最近上报时刻，UTC，ISO-8601
 * @param modules       模块清单
 */
public record DeviceModulesView(String deviceId, int reportVersion, String reportedAtUtc,
                                List<ModuleEntryView> modules) {
}
