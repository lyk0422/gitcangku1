package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FirmwareRequirement;

/**
 * 固件模块需求明细视图（诊断查询，只读）。
 */
public record FirmwareRequirementView(String model, String firmwareVersion, String moduleName,
                                      String minVersion, String updatedAtUtc) {

    public static FirmwareRequirementView of(FirmwareRequirement requirement) {
        return new FirmwareRequirementView(requirement.model(), requirement.firmwareVersion(),
                requirement.moduleName(), requirement.minVersion(), requirement.updatedAtUtc());
    }
}
