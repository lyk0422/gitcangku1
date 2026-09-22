package com.example.starter.artifact.repository;

/**
 * 制品版本行记录。
 *
 * @param id        自增主键
 * @param name      制品名称
 * @param version   制品版本，正整数
 * @param retracted 是否已撤回
 */
public record ArtifactRow(long id, String name, int version, boolean retracted) {
}
