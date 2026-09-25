package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 制品版本许可证登记请求。
 *
 * @param license 许可证标识；未提供或为空表示清除登记并恢复为 UNKNOWN
 */
public record SetLicenseRequest(
        @NotBlank String name,
        @Positive int version,
        @Size(max = 64) String license) {
}
