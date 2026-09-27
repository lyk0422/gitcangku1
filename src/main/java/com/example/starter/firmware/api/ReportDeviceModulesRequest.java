package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备模块清单上报请求。清单为全量替换：后提交的上报整体覆盖该设备此前清单。
 */
public record ReportDeviceModulesRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid List<ModuleEntry> modules) {
}
