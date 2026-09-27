package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 摘要登记结果视图。
 *
 * @param registeredBy      登记摘要的操作者（发布角色）
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record ChecksumResponse(
        String name,
        int version,
        String algorithm,
        String digest,
        String registeredBy,
        Instant registeredAt,
        long repositoryVersion) {
}
