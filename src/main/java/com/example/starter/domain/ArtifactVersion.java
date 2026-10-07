package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的普通依赖与 peer 依赖）。
 *
 * <p>普通依赖 {@code dependencies} 建立可达边；peer 依赖 {@code peers} 不建立可达边，
 * 仅约束普通依赖图中已经可达的同名制品版本（详见 {@link LockResolver}）。
 *
 * @param withdrawn true 表示该版本已撤回
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies,
        List<DependencyRange> peers) {

    public ArtifactVersion {
        dependencies = List.copyOf(dependencies);
        peers = List.copyOf(peers);
    }
}
