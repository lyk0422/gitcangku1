package com.example.starter.artifact.dto;

import java.util.List;

/**
 * 仓库整体视图。
 *
 * @param repositoryVersion 当前仓库版本号
 * @param artifacts         全部制品版本（含已撤回），按名称、版本升序
 */
public record RepositoryView(long repositoryVersion, List<ArtifactView> artifacts) {
}
