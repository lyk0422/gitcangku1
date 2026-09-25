package com.example.starter.api.dto;

/**
 * 许可证违规诊断项，字段顺序即对外稳定排序（名称, 版本）。
 *
 * @param name    违规制品名称
 * @param version 解析出的精确版本号
 * @param license 该版本登记的许可证；null 表示未登记（UNKNOWN）
 * @param reason  违规原因：LICENSE_NOT_ALLOWED 或 UNKNOWN_LICENSE_REJECTED
 */
public record LicenseViolationResponse(String name, int version, String license, String reason) {
}
