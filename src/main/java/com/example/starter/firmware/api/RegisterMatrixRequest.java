package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 固件模块需求矩阵登记/修订请求。同一固件版本重复提交为全量修订，成功后矩阵版本加一。
 * requirements 为该固件直接声明的模块需求；dependencies 为全局模块依赖边，登记后完整后态不得成环。
 */
public record RegisterMatrixRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotEmpty @Valid List<RequirementInput> requirements,
        @Valid List<DependencyInput> dependencies) {

    /**
     * 单个直接模块需求。
     */
    public record RequirementInput(
            @NotBlank @Size(max = 64) String module,
            @NotBlank @Size(max = 32) String minVersion) {
    }

    /**
     * 模块依赖边：module 依赖 dependsOn 的 minVersion 及以上。
     */
    public record DependencyInput(
            @NotBlank @Size(max = 64) String module,
            @NotBlank @Size(max = 64) String dependsOn,
            @NotBlank @Size(max = 32) String minVersion) {
    }
}
