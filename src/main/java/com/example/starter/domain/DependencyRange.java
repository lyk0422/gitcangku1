package com.example.starter.domain;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion]，携带作用域。
 *
 * @param scope PUBLIC=当前作用域内名称唯一版本；PRIVATE=目标进入子作用域
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion,
                              DependencyScope scope) {

    /** 兼容旧声明：未指定作用域时默认 PUBLIC。 */
    public DependencyRange(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, DependencyScope.PUBLIC);
    }
}
