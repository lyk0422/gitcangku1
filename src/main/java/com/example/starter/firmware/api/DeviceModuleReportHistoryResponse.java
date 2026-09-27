package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeviceModuleReport;

import java.util.List;

/**
 * 设备模块上报历史（只读），按上报版本升序。
 */
public record DeviceModuleReportHistoryResponse(String deviceId, List<DeviceModuleReport> reports) {
}
