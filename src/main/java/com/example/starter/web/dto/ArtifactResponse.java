package com.example.starter.web.dto;

import java.util.List;

/**
 * 制品登记成功响应。
 *
 * @param name         制品名称
 * @param version      制品版本号
 * @param dependencies 已登记的依赖声明（按名称排序）
 */
public record ArtifactResponse(
        String name,
        Integer version,
        List<DependencyView> dependencies
) {
}
