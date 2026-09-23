package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param contentDigest     登记时冻结的制品内容 SHA-256 摘要（64 位十六进制）
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 */
public record ArtifactResponse(
        String name,
        int version,
        boolean withdrawn,
        String contentDigest,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies) {
}
