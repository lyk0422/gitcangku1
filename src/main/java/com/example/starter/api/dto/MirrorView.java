package com.example.starter.api.dto;

/**
 * 镜像源登记明细视图。
 *
 * @param available 当前是否可用：false=不可用（不参与解析与故障切换，记录保留）
 */
public record MirrorView(String mirrorId, int priority, boolean available) {
}
