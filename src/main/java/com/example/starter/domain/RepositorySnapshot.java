package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 某一时刻仓库的一致性快照：版本号、全部制品版本与命名空间许可证策略，供锁定解析使用。
 *
 * @param repositoryVersion 读取时的仓库版本号
 * @param artifacts         名称 -> 该名称下全部版本（含撤回，版本号降序）
 * @param policies          命名空间 -> 当前许可证策略（无策略的命名空间不在 Map 中）
 */
public record RepositorySnapshot(
        long repositoryVersion,
        Map<String, List<ArtifactVersion>> artifacts,
        Map<String, NamespacePolicy> policies) {

    public RepositorySnapshot {
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    /** 向后兼容的无策略快照（仅供未携带策略的场景使用）。 */
    public RepositorySnapshot(long repositoryVersion, Map<String, List<ArtifactVersion>> artifacts) {
        this(repositoryVersion, artifacts, Map.of());
    }
}
