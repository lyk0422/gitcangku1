package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 本地配置修改请求：整体替换设备配置（至多10个键，值0~1000000）。
 * 携带全局 requestId 与非空操作者；expectedConfigVersion 必须等于当前配置版本，
 * 且设备无进行中任务时才允许修改。
 */
public record ConfigModifyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) Integer expectedConfigVersion,
        @NotNull @Size(max = 10) Map<@NotBlank @Size(max = 64) String,
                @NotNull @Min(0) @Max(1000000) Integer> entries) {
}
