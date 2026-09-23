package com.example.starter.api.dto;

/**
 * 响应中的依赖区间视图。
 *
 * @param optional 是否可选依赖
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion, boolean optional) {

    /** 旧视图便捷构造器：必选依赖。 */
    public DependencyView(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }
}
