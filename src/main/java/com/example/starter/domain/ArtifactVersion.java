package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param withdrawn true 表示该版本已撤回
 * @param sizeBytes 制品体积，单位字节，非负；历史未声明版本按 0 处理
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies,
        long sizeBytes) {

    /** 兼容未声明体积的构造：体积按 0 处理。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, dependencies, 0L);
    }
}
