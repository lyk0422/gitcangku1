package com.example.starter.api.dto;

/**
 * 镜像源视图：仅含镜像标识与优先级，按优先级升序（1 最高）排列。
 * 用于锁文件固化的镜像清单与故障切换查询结果。
 */
public record MirrorView(String mirrorId, int priority) {
}
