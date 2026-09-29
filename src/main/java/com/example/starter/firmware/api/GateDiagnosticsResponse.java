package com.example.starter.firmware.api;

import java.util.List;

/**
 * 门禁诊断响应（只读）：按型号（可再按目标/回退版本）过滤，门禁按ID稳定排序。
 */
public record GateDiagnosticsResponse(String model, String targetVersion, String rollbackVersion,
                                      int totalCount, List<GateView> gates) {
}
