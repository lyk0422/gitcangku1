package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 制品版本许可证登记/修改结果视图。
 *
 * @param license           登记后的许可证标识；null 表示 UNKNOWN
 * @param withdrawn         该版本当前撤回状态
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record LicenseResponse(
        String name,
        int version,
        String license,
        boolean withdrawn,
        long repositoryVersion,
        Instant updatedAt) {
}
