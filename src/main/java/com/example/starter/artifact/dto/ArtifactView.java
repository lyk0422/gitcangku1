package com.example.starter.artifact.dto;

import java.util.List;

/**
 * 仓库中一个制品版本的视图。
 *
 * @param id           制品记录 id
 * @param name         制品名称
 * @param version      制品版本
 * @param retracted    是否已撤回
 * @param dependencies 依赖声明（创建后不可改）
 */
public record ArtifactView(long id, String name, int version, boolean retracted,
                           List<DependencyDto> dependencies) {
}
