package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 本地配置修改请求：逐键设置（存在则覆盖值，不存在则新增），携带期望配置版本。
 * 仅当设备无进行中（PENDING）投放任务时允许；合并后键数超过 10 拒绝。
 */
public record UpdateDeviceConfigRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @Min(1) int expectedConfigVersion,
        @NotNull @Size(min = 1, max = 10) Map<@NotBlank @Size(max = 64) String, @Min(0) @Max(1000000) Integer> entries) {
}
