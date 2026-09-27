package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁定解析时闭包内命中的“已生效”弃用项，422 响应中按制品、版本稳定排序返回。
 *
 * @param name               被弃用制品名称
 * @param version            被弃用精确版本号
 * @param replacementName    替代制品名称（与 name 相同）
 * @param replacementVersion 替代版本号
 * @param effectiveAt        弃用生效 UTC 时刻（resolvedAt &gt;= 该值）
 */
public record DeprecatedBlockedItem(
        String name,
        int version,
        String replacementName,
        int replacementVersion,
        Instant effectiveAt) {
}
