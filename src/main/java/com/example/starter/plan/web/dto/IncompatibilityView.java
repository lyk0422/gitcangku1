package com.example.starter.plan.web.dto;

/**
 * 单条站台不适配原因。
 *
 * @param reasonCode 原因码：LENGTH_EXCEEDED 超长 / POWER_INCOMPATIBLE 制式不兼容 /
 *                   ACCESSIBILITY_UNAVAILABLE 无障碍能力不满足 / PLATFORM_NOT_FOUND 站台未登记
 * @param message    人类可读说明（含实际与要求值，单位米）
 */
public record IncompatibilityView(String reasonCode, String message) {
}
