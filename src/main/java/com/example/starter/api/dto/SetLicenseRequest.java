package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 许可证登记/修订请求：为单个制品版本登记许可证标识。
 */
public record SetLicenseRequest(
        @NotBlank @Size(max = 64) String license) {
}
