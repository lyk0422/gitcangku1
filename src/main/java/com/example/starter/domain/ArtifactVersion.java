package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param withdrawn true 表示该版本已撤回
 * @param sizeBytes 制品体积，单位字节，非负；旧版本未声明时按 0 兼容
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        long sizeBytes,
        List<DependencyRange> dependencies) {

    /** 无体积信息的旧数据兼容构造：大小按 0 处理。 */
    public ArtifactVersion(long id, String name, int version, boolean withdrawn,
                           List<DependencyRange> dependencies) {
        this(id, name, version, withdrawn, 0L, dependencies);
    }
}
