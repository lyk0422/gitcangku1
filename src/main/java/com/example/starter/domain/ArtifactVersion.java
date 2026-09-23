package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖与支持平台）。
 *
 * @param withdrawn    true 表示该版本已撤回
 * @param platforms    支持的目标平台集合（os/arch）；空集合或包含 ANY 表示平台无关。
 *                     历史无平台数据迁移为空集合，按 ANY 处理
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies,
        List<String> platforms) {

    /** 兼容旧调用：不携带平台信息（按 ANY 处理）。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, dependencies, List.of());
    }

    /**
     * 是否支持指定目标平台：显式列出该平台，或声明 ANY（含历史无平台数据）。
     */
    public boolean supports(String targetPlatform) {
        if (platforms == null || platforms.isEmpty()) {
            return true;
        }
        return platforms.contains("ANY") || platforms.contains(targetPlatform);
    }
}
