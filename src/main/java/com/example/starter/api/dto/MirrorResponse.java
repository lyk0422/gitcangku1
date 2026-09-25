package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 镜像登记/可用性切换写操作的响应视图。
 *
 * @param available         操作完成后镜像是否可用
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record MirrorResponse(
        String name,
        int version,
        String mirrorId,
        int priority,
        boolean available,
        long repositoryVersion,
        Instant createdAt) {
}
