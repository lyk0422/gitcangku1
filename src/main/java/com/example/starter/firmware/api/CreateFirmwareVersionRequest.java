package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建固件版本兼容声明请求：型号集合、最低内存（MB）与必需能力标签。
 */
public record CreateFirmwareVersionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String name,
        @NotEmpty List<@NotBlank @Size(max = 64) String> models,
        @Min(0) int minMemoryMb,
        @NotNull List<@NotBlank @Size(max = 64) String> requiredTags) {
}
