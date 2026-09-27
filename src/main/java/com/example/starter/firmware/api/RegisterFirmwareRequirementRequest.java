package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记固件模块需求请求：某型号某固件版本声明所需模块及最低模块版本。
 * firmwareVersion/minVersion 的点分数字合法性在业务层校验，非法返回 422。
 */
public record RegisterFirmwareRequirementRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String firmwareVersion,
        @NotBlank @Size(max = 64) String moduleName,
        @NotBlank @Size(max = 64) String minVersion) {
}
