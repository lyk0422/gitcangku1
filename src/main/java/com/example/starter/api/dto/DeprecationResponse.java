package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 弃用窗口视图。
 *
 * @param effectiveAt 弃用生效时刻（UTC，左闭）
 * @param replacementVersion 同制品替代版本号
 * @param reason 弃用说明；null 表示未填写
 * @param revoked 是否已撤销（撤销记录保留，不删除）
 * @param revokedAt 撤销时刻（UTC）；null 表示未撤销
 * @param createdAt 登记时刻（UTC）
 */
public record DeprecationResponse(
        long id,
        String name,
        int version,
        Instant effectiveAt,
        int replacementVersion,
        String reason,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
