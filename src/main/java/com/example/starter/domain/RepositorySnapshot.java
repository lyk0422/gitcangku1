package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 某一时刻仓库的一致性快照：版本号与全部制品版本，供锁定解析使用。
 *
 * @param repositoryVersion 读取时的仓库版本号
 * @param artifacts          名称 -> 该名称下全部版本（含撤回，版本号降序）
 */
public record RepositorySnapshot(
        long repositoryVersion,
        Map<String, List<ArtifactVersion>> artifacts) {
}
