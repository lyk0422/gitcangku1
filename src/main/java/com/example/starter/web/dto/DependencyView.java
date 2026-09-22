package com.example.starter.web.dto;

/**
 * 依赖声明视图：闭区间 [minVersion, maxVersion]。
 */
public record DependencyView(
        String name,
        Integer minVersion,
        Integer maxVersion
) {
}
