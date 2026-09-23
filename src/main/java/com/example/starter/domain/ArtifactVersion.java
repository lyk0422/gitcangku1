package com.example.starter.domain;

import java.util.List;
import java.util.Set;

/**
 * 单个制品版本的不可变快照（含其声明的依赖与可用平台）。
 *
 * @param withdrawn  true 表示该版本已撤回
 * @param platforms  该版本可用平台清单；空集合表示不限平台（任意平台可用）
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies,
        Set<String> platforms) {

    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, dependencies, Set.of());
    }

    /**
     * 在指定目标平台上是否可用：未撤回，且平台清单为空（不限）或显式包含该平台。
     */
    public boolean availableOn(String platform) {
        if (withdrawn) {
            return false;
        }
        if (platform == null) {
            return true;
        }
        return platforms.isEmpty() || platforms.contains(platform);
    }
}
