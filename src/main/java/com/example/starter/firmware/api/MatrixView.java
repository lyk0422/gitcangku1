package com.example.starter.firmware.api;

import java.util.List;

/**
 * 固件兼容矩阵视图。requirements 为该固件的直接需求，dependencies 为登记后全局模块依赖图。
 */
public record MatrixView(String firmwareVersion, int matrixVersion, String updatedAtUtc,
                         List<RequirementView> requirements, List<DependencyView> dependencies) {

    public record RequirementView(String module, String minVersion) {
    }

    public record DependencyView(String module, String dependsOn, String minVersion) {
    }
}
