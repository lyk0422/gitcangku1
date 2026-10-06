package com.example.starter.api.dto;

import com.example.starter.domain.DependencyScope;

/**
 * 响应中的依赖区间视图。
 *
 * @param scope 依赖作用域：PUBLIC 或 PRIVATE
 */
public record DependencyView(String name, int minimumVersion, int maximumVersion,
                             DependencyScope scope) {
}
