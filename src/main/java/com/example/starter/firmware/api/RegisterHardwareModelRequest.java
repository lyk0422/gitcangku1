package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记已知硬件型号请求。硬件型号目录登记后不可变，矩阵型号集合只允许引用已知型号。
 */
public record RegisterHardwareModelRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String hardwareModel) {
}
