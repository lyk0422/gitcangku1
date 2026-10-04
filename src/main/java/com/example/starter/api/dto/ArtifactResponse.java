package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 制品版本视图。
 *
 * @param withdrawn         是否撤回：true=已撤回（记录保留）
 * @param repositoryVersion 该次写操作完成后的仓库版本号
 * @param capabilities      该版本声明的能力名（0~3 个，按名称升序）
 */
public record ArtifactResponse(
        String name,
        int version,
        boolean withdrawn,
        long repositoryVersion,
        Instant createdAt,
        List<DependencyView> dependencies,
        List<String> capabilities) {
}