package com.example.starter.api.dto;

import com.example.starter.domain.DependencyScope;

import java.util.List;

/**
 * 锁文件中冻结的一条依赖边（按登记时的声明原样冻结）。
 *
 * @param scopePath      声明该依赖的制品所在作用域路径（结构化数组，根为空数组）
 * @param fromName       声明依赖的制品名称
 * @param name           依赖目标名称
 * @param scope          依赖作用域：PUBLIC 或 PRIVATE
 * @param minimumVersion 依赖最低版本（含）
 * @param maximumVersion 依赖最高版本（含）
 */
public record LockEdgeResponse(List<String> scopePath, String fromName, String name,
                               DependencyScope scope, int minimumVersion, int maximumVersion) {
}
