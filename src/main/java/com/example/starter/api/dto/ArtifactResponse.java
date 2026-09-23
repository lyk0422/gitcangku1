package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param platforms         支持平台集合（os/arch），仅含 ANY 表示全平台
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record ArtifactResponse(
        String name,
        int version,
        boolean withdrawn,
        List<String> platforms,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies) {
}
