package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param withdrawn true 表示该版本已撤回
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies) {
}
