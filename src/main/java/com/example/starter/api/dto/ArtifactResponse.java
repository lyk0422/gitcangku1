package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 * @param platforms         该版本可用平台清单，空清单表示不限平台
 */
public record ArtifactResponse(
        String name,
        int version,
        boolean withdrawn,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies,
        List<String> platforms) {

    public ArtifactResponse(String name, int version, boolean withdrawn, long repositoryVersion,
                            Instant createdAt, List<DependencyView> dependencies) {
        this(name, version, withdrawn, repositoryVersion, createdAt, dependencies, List.of());
    }
}
