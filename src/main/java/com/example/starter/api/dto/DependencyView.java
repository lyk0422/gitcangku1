package com.example.starter.api.dto;

import java.util.List;

/**
 * 响应中的依赖区间视图，exclusions 为升序的路径排除名称。
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion,
                             List<String> exclusions) {

    public DependencyView {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }

    /** 无排除的便捷构造。 */
    public DependencyView(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, List.of());
    }
}
