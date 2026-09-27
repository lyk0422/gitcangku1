package com.example.starter.firmware.domain;

/**
 * 模块间依赖边（兼容矩阵当前态）：module 依赖 dependsOn，且 dependsOn 不得低于 minVersion。
 *
 * @param module     模块名
 * @param dependsOn  依赖的模块名
 * @param minVersion 依赖模块最小版本，点分数字格式
 */
public record ModuleDependency(String module, String dependsOn, String minVersion) {
}
