package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FirmwareVersion;

/**
 * 固件版本视图。
 */
public record FirmwareVersionView(long firmwareId, String model, String version, String packageUri) {

    public static FirmwareVersionView of(FirmwareVersion firmware) {
        return new FirmwareVersionView(firmware.id(), firmware.model(), firmware.version(),
                firmware.packageUri());
    }
}
