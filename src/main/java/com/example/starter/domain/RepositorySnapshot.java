package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 某一时刻仓库的一致性快照：版本号、全部制品版本与有效弃用窗口，供锁定解析使用。
 *
 * @param repositoryVersion 读取时的仓库版本号
 * @param artifacts         名称 -> 该名称下全部版本（含撤回，版本号降序）
 * @param deprecations      名称 -> (版本号 -> 有效弃用窗口)，仅含未撤销窗口
 */
public record RepositorySnapshot(
        long repositoryVersion,
        Map<String, List<ArtifactVersion>> artifacts,
        Map<String, Map<Integer, DeprecationWindow>> deprecations) {

    /** 兼容无弃用窗口的调用方（如解析器单元测试）。 */
    public RepositorySnapshot(long repositoryVersion, Map<String, List<ArtifactVersion>> artifacts) {
        this(repositoryVersion, artifacts, Map.of());
    }
}
