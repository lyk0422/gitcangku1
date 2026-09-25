package com.example.starter.api.dto;

/**
 * 锁文件中固化的镜像源条目：锁定时按优先级排序并过滤不可用后的快照，
 * 后续镜像可用性变更不回写。
 */
public record LockMirrorView(String mirrorId, int priority) {
}
