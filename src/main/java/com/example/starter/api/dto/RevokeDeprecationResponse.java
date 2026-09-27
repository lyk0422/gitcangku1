package com.example.starter.api.dto;

/**
 * 撤销弃用窗口响应。
 *
 * @param repositoryVersion 本次撤销完成后的仓库版本号
 * @param window 撤销后的窗口视图（revoked=true，revokedAt 已固化）
 */
public record RevokeDeprecationResponse(
        long repositoryVersion,
        DeprecationResponse window) {
}
