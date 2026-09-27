package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备模块清单全量上报请求。重复提交为全量替换，上报版本加一；版本须为公开点分数字版本。
 */
public record DeviceModulesReportRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid List<ModuleVersionInput> modules) {

    public record ModuleVersionInput(
            @NotBlank @Size(max = 64) String module,
            @NotBlank @Size(max = 32) String version) {
    }
}
