package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param riskLevel         该制品版本当前风险级别：NONE/LOW/MEDIUM/HIGH/CRITICAL
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record ArtifactResponse(
        String name,
        int version,
        boolean withdrawn,
        String riskLevel,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies) {
}
