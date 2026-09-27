package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备模块上报请求：整体替换设备当前模块清单，上报版本加一并固化摘要。
 * 模块版本必须为点分数字格式（非法返回422）。
 */
public record DeviceModuleReportRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Valid List<ModuleEntry> modules) {

    /**
     * 模块清单条目。
     */
    public record ModuleEntry(
            @NotBlank @Size(max = 64) String module,
            @NotBlank @Size(max = 64) String version) {
    }
}
