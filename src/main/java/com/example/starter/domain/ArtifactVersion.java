package com.example.starter.domain;

import java.util.List;

/**
 * 单个制品版本的不可变快照（含其声明的依赖）。
 *
 * @param id           制品版本主键
 * @param name         制品名称
 * @param version      版本号
 * @param withdrawn    true 表示该版本已撤回
 * @param license      已登记许可证标识；null 表示未登记（UNKNOWN）
 * @param dependencies 声明的依赖区间
 */
public record ArtifactVersion(
        long id,
        String name,
        int version,
        boolean withdrawn,
        String license,
        List<DependencyRange> dependencies) {
}
