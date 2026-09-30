package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.HardwareRequirement;

import java.util.List;

/**
 * 固件版本硬件兼容要求视图。
 */
public record HardwareRequirementView(String firmwareVersion, Integer minMemoryMb,
                                      List<String> models, List<String> tags) {

    public static HardwareRequirementView of(HardwareRequirement requirement) {
        return new HardwareRequirementView(requirement.firmwareVersion(), requirement.minMemoryMb(),
                requirement.models(), requirement.tags());
    }
}
