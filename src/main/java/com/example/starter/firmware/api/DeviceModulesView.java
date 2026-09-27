package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeviceModuleEntry;

import java.util.List;

/**
 * 设备模块清单视图（只读）：模块按名升序；moduleDigest 为清单摘要，从未上报为 null。
 */
public record DeviceModulesView(String deviceId, int moduleReportVersion, String moduleDigest,
                                List<DeviceModuleEntry> modules) {
}
