package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 兼容矩阵登记请求：固件需求与模块依赖合并进当前矩阵，矩阵版本加一。
 * 版本必须为点分数字格式（非法返回422）；合并后的模块依赖图不得成环。
 */
public record MatrixRegisterRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Valid List<RequirementEntry> firmwareRequirements,
        @NotNull @Valid List<DependencyEntry> moduleDependencies) {

    /**
     * 固件需求条目：固件版本所需模块及最小版本。
     */
    public record RequirementEntry(
            @NotBlank @Size(max = 64) String firmwareVersion,
            @NotBlank @Size(max = 64) String module,
            @NotBlank @Size(max = 64) String minVersion) {
    }

    /**
     * 模块依赖条目：module 依赖 dependsOn，且 dependsOn 不得低于 minVersion。
     */
    public record DependencyEntry(
            @NotBlank @Size(max = 64) String module,
            @NotBlank @Size(max = 64) String dependsOn,
            @NotBlank @Size(max = 64) String minVersion) {
    }
}
