package com.example.starter.api.dto;

import java.util.List;

/**
 * 响应中的依赖区间视图，exclusions 为该边声明的排除名称（升序）。
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion,
                             List<String> exclusions) {

    public DependencyView {
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
    }
}
