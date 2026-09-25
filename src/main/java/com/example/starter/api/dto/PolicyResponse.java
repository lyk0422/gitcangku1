package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 命名空间许可证策略视图，allowedLicenses 按字典序排列。
 *
 * @param version          当前策略版本号
 * @param rejectUnknown    是否拒绝 UNKNOWN
 * @param allowedLicenses  允许的许可证标识集合（排序）
 * @param updatedAt        最后修改时间，UTC
 */
public record PolicyResponse(
        String namespace,
        long version,
        boolean rejectUnknown,
        List<String> allowedLicenses,
        Instant updatedAt) {
}
