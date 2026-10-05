package com.example.starter.domain;

import java.util.List;

/**
 * 依赖闭区间 [minimumVersion, maximumVersion]，可携带边级路径排除名称。
 *
 * @param exclusions 经过该边之后、其子图中应忽略的边名称（升序不可变，最多 3 个；
 *                   不作用于该边目标本身）；空列表表示无排除
 */
public record DependencyRange(String name, int minimumVersion, int maximumVersion,
                              List<String> exclusions) {

    public DependencyRange {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }

    /** 无排除的便捷构造。 */
    public DependencyRange(String name, int minimumVersion, int maximumVersion) {
        this(name, minimumVersion, maximumVersion, List.of());
    }
}
