package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param sizeBytes         制品体积，单位字节，非负；未声明体积的旧版本按 0
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record ArtifactResponse(
        String name,
        int version,
        long sizeBytes,
        boolean withdrawn,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies) {
}
