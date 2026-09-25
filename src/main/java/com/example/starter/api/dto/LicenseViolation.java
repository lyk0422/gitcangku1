package com.example.starter.api.dto;

/**
 * 单条许可证违规诊断：制品、版本、锁定时取到的许可证与违规原因。
 *
 * @param reason 违规原因码：LICENSE_NOT_ALLOWED=许可证不在允许集合；
 *               UNKNOWN_LICENSE_REJECTED=未登记许可证且策略拒绝 UNKNOWN
 */
public record LicenseViolation(
        String name,
        int version,
        String license,
        String reason) {
}
