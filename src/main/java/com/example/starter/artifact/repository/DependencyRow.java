package com.example.starter.artifact.repository;

/**
 * 依赖声明行记录。
 *
 * @param artifactId 所属制品版本 id
 * @param depName    依赖的制品名称
 * @param minVersion 最低版本（闭区间，含）
 * @param maxVersion 最高版本（闭区间，含）
 */
public record DependencyRow(long artifactId, String depName, int minVersion, int maxVersion) {
}
