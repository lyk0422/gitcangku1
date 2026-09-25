package com.example.starter.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 许可证登记请求体（name/version 由路径提供）。
 *
 * @param license 许可证标识；缺省、null 或空串表示清除登记恢复为 UNKNOWN
 */
public record LicenseBody(@Size(max = 64) String license) {
}
