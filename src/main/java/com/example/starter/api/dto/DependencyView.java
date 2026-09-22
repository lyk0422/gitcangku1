package com.example.starter.api.dto;

/**
 * 响应中的依赖区间视图。
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion) {
}
