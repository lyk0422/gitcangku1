package com.example.starter.domain;

/**
 * 一条许可证违规诊断：哪个解析版本因何违反命名空间策略。
 *
 * @param name    制品名称（即命名空间）
 * @param version 解析出的精确版本号
 * @param license 该版本登记的许可证；null 表示 UNKNOWN（未登记）
 * @param reason  违规原因，见 {@link #REASON_NOT_ALLOWED} 与 {@link #REASON_UNKNOWN_REJECTED}
 */
public record LicenseViolation(String name, int version, String license, String reason) {

    /** 许可证已登记但不在命名空间允许集合内。 */
    public static final String REASON_NOT_ALLOWED = "LICENSE_NOT_ALLOWED";

    /** 许可证未登记（UNKNOWN）且命名空间策略拒绝 UNKNOWN。 */
    public static final String REASON_UNKNOWN_REJECTED = "UNKNOWN_LICENSE_REJECTED";
}
