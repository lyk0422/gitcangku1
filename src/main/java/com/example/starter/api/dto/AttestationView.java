package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 单条见证视图。
 *
 * @param role       见证发布角色
 * @param checksum   见证提交的摘要
 * @param active     true=有效，false=已撤销
 * @param attestedAt 见证提交时间，UTC
 * @param revokedAt  撤销时间，UTC；未撤销为 null
 * @param revokedBy  撤销操作者；未撤销为 null
 */
public record AttestationView(
        String role,
        String checksum,
        boolean active,
        Instant attestedAt,
        Instant revokedAt,
        String revokedBy) {
}
