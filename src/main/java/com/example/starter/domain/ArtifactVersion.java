package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param withdrawn  true 表示该版本已撤回
 * @param platforms  支持的目标平台集合，元素为 {@code os/arch} 或单一 {@code ANY}；
 *                   旧数据迁移为仅含 {@code ANY}
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<String> platforms,
        List<DependencyRange> dependencies) {

    public ArtifactVersion {
        platforms = List.copyOf(platforms);
        dependencies = List.copyOf(dependencies);
    }

    /** 无平台（ANY）的便捷构造器，兼容旧调用。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, List.of(Platforms.ANY), dependencies);
    }

    /**
     * 是否支持指定目标平台：包含精确的 {@code os/arch}，或声明了 ANY。
     *
     * @param targetPlatform 形如 {@code os/arch} 的目标平台（非 ANY）
     */
    public boolean supports(String targetPlatform) {
        return platforms.contains(Platforms.ANY) || platforms.contains(targetPlatform);
    }
}
