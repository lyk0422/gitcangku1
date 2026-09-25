package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 许可证登记结果视图。
 *
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record LicenseResponse(
        String name,
        int version,
        String license,
        long repositoryVersion,
        Instant updatedAt) {
}
