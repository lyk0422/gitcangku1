package com.example.starter.domain;

import java.util.List;
import java.util.Set;

/**
 * 命名空间许可证策略的不可变快照。
 *
 * <p>命名空间等于制品名称；策略只影响其后的新锁定，不追溯已有锁文件。
 *
 * @param namespace       命名空间名称（等于制品名称）
 * @param version         策略版本号：首次创建为 1，每次修改加一
 * @param rejectUnknown   true 表示拒绝未登记许可证（UNKNOWN）的制品版本
 * @param allowedLicenses 允许的许可证标识集合；UNKNOWN 不由该集合控制
 */
public record NamespacePolicy(
        String namespace,
        long version,
        boolean rejectUnknown,
        Set<String> allowedLicenses) {

    public NamespacePolicy {
        allowedLicenses = allowedLicenses == null ? Set.of() : Set.copyOf(allowedLicenses);
    }

    /**
     * 校验一个解析版本的许可证。
     *
     * @param license 已登记许可证；null 表示 UNKNOWN
     * @return 违规原因；null 表示通过
     */
    public String check(String license) {
        if (license == null) {
            return rejectUnknown ? LicenseViolation.REASON_UNKNOWN_REJECTED : null;
        }
        return allowedLicenses.contains(license) ? null : LicenseViolation.REASON_NOT_ALLOWED;
    }

    /** 排序后的允许许可证列表，供响应输出稳定顺序。 */
    public List<String> sortedAllowedLicenses() {
        return allowedLicenses.stream().sorted().toList();
    }
}
