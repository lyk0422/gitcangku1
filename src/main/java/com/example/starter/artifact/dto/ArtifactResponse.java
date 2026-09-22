package com.example.starter.artifact.dto;

/**
 * 制品登记成功响应。
 *
 * @param id                制品记录 id
 * @param name              制品名称
 * @param version           制品版本
 * @param retracted         是否已撤回（新建恒为 false）
 * @param repositoryVersion 登记后的仓库版本号
 */
public record ArtifactResponse(long id, String name, int version, boolean retracted,
                               long repositoryVersion) {
}
