package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 固件硬件兼容矩阵，按固件版本一行。
 *
 * @param firmwareVersion 固件版本（发布单目标版本）
 * @param matrixVersion   兼容矩阵版本，从 1 开始，每次修改成功加一；0 表示从未配置
 * @param allowedModels   允许的硬件型号集合（升序去重）；空集合表示兼容全部型号
 */
public record FirmwareCompat(String firmwareVersion, int matrixVersion, List<String> allowedModels) {

    /**
     * 未配置矩阵时的默认视图：版本 0，兼容全部型号。
     */
    public static FirmwareCompat unconfigured(String firmwareVersion) {
        return new FirmwareCompat(firmwareVersion, 0, List.of());
    }

    /**
     * 空集合兼容全部型号；非空集合仅允许列出的硬件型号。
     */
    public boolean allows(String hardwareModel) {
        return allowedModels.isEmpty() || allowedModels.contains(hardwareModel);
    }
}
