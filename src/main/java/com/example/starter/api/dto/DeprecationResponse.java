package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 弃用窗口视图，字段时间均为 UTC；note/revokedAt 为 null 时语义为“未提供/未撤销”。
 *
 * @param id                 窗口 ID
 * @param name               被弃用制品名称
 * @param version            被弃用制品版本号
 * @param effectiveAt        弃用生效 UTC 时刻（左闭：解析时刻 &gt;= 该值即生效）
 * @param replacementName    替代制品名称（与 name 相同）
 * @param replacementVersion 替代版本号
 * @param note               弃用说明；null 表示未提供
 * @param status             ACTIVE 或 REVOKED
 * @param revokedAt          撤销 UTC 时刻；null 表示未撤销
 * @param createdAt          窗口首次登记 UTC 时刻
 */
public record DeprecationResponse(
        long id,
        String name,
        int version,
        Instant effectiveAt,
        String replacementName,
        int replacementVersion,
        String note,
        String status,
        Instant revokedAt,
        Instant createdAt) {
}
