package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CompatMatrix;

import java.util.List;

/**
 * 固件硬件兼容矩阵视图。version 为 0 表示该固件从未配置矩阵，即兼容全部硬件型号。
 */
public record MatrixView(String firmwareVersion, int version, List<String> models, boolean compatibleWithAll) {

    public static MatrixView of(CompatMatrix matrix) {
        return new MatrixView(matrix.firmwareVersion(), matrix.version(), matrix.models(), matrix.models().isEmpty());
    }

    /**
     * 未配置矩阵：版本 0、空集合、兼容全部型号。
     */
    public static MatrixView unconfigured(String firmwareVersion) {
        return new MatrixView(firmwareVersion, 0, List.of(), true);
    }
}
