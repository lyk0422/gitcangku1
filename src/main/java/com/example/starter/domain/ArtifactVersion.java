package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖与能力）。
 *
 * @param withdrawn    true 表示该版本已撤回
 * @param capabilities 该版本声明的能力名（0~3 个，登记后不可改）
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        List<DependencyRange> dependencies,
        List<String> capabilities) {
}