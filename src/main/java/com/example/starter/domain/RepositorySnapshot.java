package com.example.starter.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 某一时刻仓库的一致性快照：版本号、全部制品版本及当前生效的互斥能力规则，供锁定解析使用。
 *
 * @param repositoryVersion 读取时的仓库版本号
 * @param artifacts         名称 -> 该名称下全部版本（含撤回，版本号降序）
 * @param exclusions        读取时生效的互斥能力对（无序，规范化存储）
 */
public record RepositorySnapshot(
        long repositoryVersion,
        Map<String, List<ArtifactVersion>> artifacts,
        Set<CapabilityPair> exclusions) {
}