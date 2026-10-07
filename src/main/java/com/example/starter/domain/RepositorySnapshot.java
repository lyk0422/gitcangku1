package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 某一时刻仓库的一致性快照：版本号、全部制品版本与协调组规则，供锁定解析使用。
 *
 * @param repositoryVersion  读取时的仓库版本号
 * @param artifacts          名称 -> 该名称下全部版本（含撤回，版本号降序）
 * @param coordinationGroups 裁决时生效的协调组及允许元组（组 ID 升序）
 */
public record RepositorySnapshot(
        long repositoryVersion,
        Map<String, List<ArtifactVersion>> artifacts,
        List<CoordinationGroup> coordinationGroups) {

    /** 无协调组约束的快照（兼容旧调用方）。 */
    public RepositorySnapshot(long repositoryVersion,
                              Map<String, List<ArtifactVersion>> artifacts) {
        this(repositoryVersion, artifacts, List.of());
    }
}
