package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备隔离请求：expectedVersion 必须等于设备当前固件版本（乐观校验）。
 * 幂等指纹含操作者、设备版本、操作与原因。
 */
public record QuarantineRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String reasonCode,
        @NotBlank @Size(max = 64) String expectedVersion) {
}
