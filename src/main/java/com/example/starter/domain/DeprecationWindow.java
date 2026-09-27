package com.example.starter.domain;

import java.time.Instant;

/**
 * 制品版本弃用窗口的不可变视图。
 *
 * <p>时间一律为 UTC；区间左闭右开：解析时刻 {@code t >= effectiveAt} 时窗口已生效，
 * {@code t < effectiveAt} 时仅产生警告。
 *
 * @param id                 窗口 ID
 * @param artifactId         被弃用制品版本 ID
 * @param name               被弃用制品名称
 * @param version            被弃用制品版本号
 * @param effectiveAt        弃用生效 UTC 时刻
 * @param replacementId      替代制品版本 ID（登记时保证与被弃用版本同名且未撤回）
 * @param replacementName    替代制品名称（与 name 相同）
 * @param replacementVersion 替代制品版本号
 * @param note               弃用说明；null 表示未提供
 * @param status             窗口状态
 * @param revokedAt          撤销 UTC 时刻；null 表示未撤销
 * @param createdAt          窗口首次登记 UTC 时刻
 */
public record DeprecationWindow(
        long id,
        long artifactId,
        String name,
        int version,
        Instant effectiveAt,
        long replacementId,
        String replacementName,
        int replacementVersion,
        String note,
        DeprecationStatus status,
        Instant revokedAt,
        Instant createdAt) {
}
