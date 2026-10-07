package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的普通依赖与 peer 依赖）。
 *
 * @param withdrawn        true 表示该版本已撤回
 * @param dependencies     普通依赖闭区间，建立可达边
 * @param peerDependencies peer 依赖闭区间，仅约束普通依赖图已可达的同名宿主，不建立可达边
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies,
        List<DependencyRange> peerDependencies) {

    /** 兼容无 peer 声明的构造：peer 列表为空。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, dependencies, List.of());
    }
}
