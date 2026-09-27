package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FirmwareRequirement;
import com.example.starter.firmware.domain.ModuleDependency;

import java.util.List;

/**
 * 当前兼容矩阵视图（只读）：需求按（固件版本，模块）升序，依赖按（模块，依赖模块）升序。
 */
public record MatrixView(int matrixVersion, List<FirmwareRequirement> requirements,
                         List<ModuleDependency> dependencies) {
}
