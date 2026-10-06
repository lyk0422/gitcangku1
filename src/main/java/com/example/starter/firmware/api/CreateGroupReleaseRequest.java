package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建固定设备组发布请求：固定2~8台同型号设备与目标版本。
 * 操作者 operator 与全部参数一起进入幂等身份。
 */
public record CreateGroupReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String toVersion,
        @NotNull @Size(min = 2, max = 8) List<@NotBlank @Size(max = 64) String> deviceIds) {
}
