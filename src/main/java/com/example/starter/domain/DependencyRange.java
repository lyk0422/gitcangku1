package com.example.starter.domain;

import java.util.List;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion]，可携带边级排除名称。
 *
 * <p>exclusions 仅允许在新登记边上声明（最多 3 个、不可重复）：语义为经过该边之后，
 * 在其整个子图中忽略以被排除名称为目标的边；排除不能移除该边目标本身。
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion,
                              List<String> exclusions) {

    public DependencyRange {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }

    /** 无排除的依赖区间。 */
    public DependencyRange(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, List.of());
    }
}
