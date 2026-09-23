package com.example.starter.api.dto;

/**
 * 响应中的依赖区间视图。
 *
 * @param optional true=可选依赖，false=必选依赖
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion, boolean optional) {

    /** 旧签名兼容：必选依赖视图。 */
    public DependencyView(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, false);
    }
}
