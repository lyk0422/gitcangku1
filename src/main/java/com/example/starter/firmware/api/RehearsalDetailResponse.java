package com.example.starter.firmware.api;

import java.util.List;

/**
 * 演练明细响应：演练视图与按 ordinal 稳定排序的每台设备结果（只读）。
 */
public record RehearsalDetailResponse(RehearsalView rehearsal, List<RehearsalDeviceView> devices) {
}
