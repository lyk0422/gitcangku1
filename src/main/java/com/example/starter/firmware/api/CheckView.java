package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CompatibilityCheck;

import java.util.List;

/**
 * 兼容判定证据视图（诊断查询，只读），字段与排序为历史稳定契约：
 * 设备模块按模块名排序，阻断明细按模块名排序，豁免命中模块按模块名排序。
 */
public record CheckView(long checkId, long releaseId, String deviceId, String outcome, int matrixVersion,
                        int exemptionVersion, List<DeviceModulesView.Item> deviceModules,
                        List<BlockedModuleView> blockedModules, List<String> exemptedModules,
                        String requestId, String checkedAtUtc) {

    public static CheckView of(CompatibilityCheck check) {
        return new CheckView(check.id(), check.releaseId(), check.deviceId(), check.outcome().name(),
                check.matrixVersion(), check.exemptionVersion(),
                check.deviceModules().stream().map(DeviceModulesView.Item::of).toList(),
                check.blockedModules().stream().map(BlockedModuleView::of).toList(),
                List.copyOf(check.exemptedModules()), check.requestId(), check.checkedAtUtc());
    }
}
