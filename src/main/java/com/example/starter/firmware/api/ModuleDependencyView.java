package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ModuleDependency;

/**
 * 模块依赖边明细视图（诊断查询，只读）。
 */
public record ModuleDependencyView(String moduleName, String dependsOn, String minVersion, String updatedAtUtc) {

    public static ModuleDependencyView of(ModuleDependency dependency) {
        return new ModuleDependencyView(dependency.moduleName(), dependency.dependsOn(),
                dependency.minVersion(), dependency.updatedAtUtc());
    }
}
