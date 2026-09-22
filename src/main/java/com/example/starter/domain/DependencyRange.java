package com.example.starter.domain;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion]。
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion) {
}
