package com.example.starter.api.dto;

/**
 * 响应中的依赖区间视图。
 *
 * @param scope 依赖作用域：PUBLIC 或 PRIVATE；旧依赖默认 PUBLIC
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion, String scope) {

    /** 兼容旧调用：未声明作用域时默认 PUBLIC。 */
    public DependencyView(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, "PUBLIC");
    }
}
