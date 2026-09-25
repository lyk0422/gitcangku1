package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FirmwareCompat;

import java.util.List;

/**
 * 固件硬件兼容矩阵视图。matrixVersion 为 0 表示从未配置（兼容全部型号）。
 */
public record FirmwareCompatView(String firmwareVersion, int matrixVersion, List<String> allowedModels) {

    public static FirmwareCompatView of(FirmwareCompat compat) {
        return new FirmwareCompatView(compat.firmwareVersion(), compat.matrixVersion(), compat.allowedModels());
    }
}
