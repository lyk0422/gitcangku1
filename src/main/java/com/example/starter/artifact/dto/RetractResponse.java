package com.example.starter.artifact.dto;

/**
 * 制品撤回成功响应。
 *
 * @param name              制品名称
 * @param version           制品版本
 * @param retracted         是否已撤回（成功撤回恒为 true）
 * @param repositoryVersion 撤回后的仓库版本号
 */
public record RetractResponse(String name, int version, boolean retracted,
                              long repositoryVersion) {
}
