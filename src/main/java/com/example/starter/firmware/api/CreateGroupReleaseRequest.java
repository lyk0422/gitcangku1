package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建组发布请求：固定 2~8 台同型号设备与目标版本。
 * 幂等身份含操作、操作者、型号、目标版本及全部成员设备。
 */
public record CreateGroupReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String toVersion,
        @NotNull @Size(min = 2, max = 8) List<@NotBlank @Size(max = 64) String> deviceIds) {
}
