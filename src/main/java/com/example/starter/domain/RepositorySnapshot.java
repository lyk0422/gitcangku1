package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 某一时刻仓库的一致性快照：版本号、全部制品版本与协调组规则，供锁定解析使用。
 *
 * @param repositoryVersion  读取时的仓库版本号
 * @param artifacts           名称 -> 该名称下全部版本（含撤回，版本号降序）
 * @param coordinationGroups  读取时的协调组规则（含组修订号与允许元组）
 */
public record RepositorySnapshot(
        long repositoryVersion,
        Map<String, List<ArtifactVersion>> artifacts,
        List<CoordinationGroup> coordinationGroups) {

    /**
     * 无协调组的快照（兼容既有调用与测试）。
     */
    public RepositorySnapshot(long repositoryVersion, Map<String, List<ArtifactVersion>> artifacts) {
        this(repositoryVersion, artifacts, List.of());
    }
}
