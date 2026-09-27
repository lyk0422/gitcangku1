package com.example.starter.firmware.domain;

/**
 * 模块间依赖边：moduleName 依赖 dependsOn 的 minVersion 及以上版本。
 *
 * @param moduleName 依赖发起模块名
 * @param dependsOn  被依赖模块名
 * @param minVersion 被依赖模块所需最小版本（点分数字）
 */
public record ModuleDependencyEdge(String moduleName, String dependsOn, String minVersion) {
}
