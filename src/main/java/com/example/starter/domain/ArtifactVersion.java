package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param withdrawn  true 表示该版本已撤回
 * @param platforms  支持平台集合，元素为 {@code os/arch}；仅含 {@code ANY} 表示全平台。
 *                   历史无平台数据迁移为 {@code ["ANY"]}
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<String> platforms,
        List<DependencyRange> dependencies) {

    /** 旧签名兼容：未显式指定平台时视为支持全平台（ANY）。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, List.of(Platforms.ANY), dependencies);
    }

    /** 是否支持指定目标平台：平台集合包含 ANY 或包含精确匹配的 {@code os/arch}。 */
    public boolean supports(String targetPlatform) {
        return platforms.contains(Platforms.ANY) || platforms.contains(targetPlatform);
    }
}
