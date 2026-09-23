package com.example.starter.api.dto;

/**
 * 响应中的依赖区间视图。
 *
 * @param optional 是否可选依赖
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion, boolean optional) {

    /** 兼容旧调用：必选依赖视图。 */
    public DependencyView(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }
}
