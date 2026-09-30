package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FirmwareVersion;

import java.util.List;

/**
 * 固件版本兼容声明视图。
 */
public record FirmwareVersionView(long versionId, String name, List<String> models,
                                  int minMemoryMb, List<String> requiredTags) {

    public static FirmwareVersionView of(FirmwareVersion version) {
        return new FirmwareVersionView(version.id(), version.name(), List.copyOf(version.models()),
                version.minMemoryMb(), List.copyOf(version.requiredTags()));
    }
}
