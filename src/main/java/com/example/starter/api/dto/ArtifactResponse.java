package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 * @param platforms         支持的目标平台集合（os/arch）；空集合表示平台无关（ANY）
 */
public record ArtifactResponse(
        String name,
        int version,
        boolean withdrawn,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies,
        List<String> platforms) {
}
