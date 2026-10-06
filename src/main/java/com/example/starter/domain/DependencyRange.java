package com.example.starter.domain;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion] 及其作用域。
 *
 * @param scope 依赖作用域；旧数据与未声明的依赖默认为 PUBLIC
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion, DependencyScope scope) {

    /** 兼容旧调用：未声明作用域时默认 PUBLIC。 */
    public DependencyRange(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, DependencyScope.PUBLIC);
    }
}
