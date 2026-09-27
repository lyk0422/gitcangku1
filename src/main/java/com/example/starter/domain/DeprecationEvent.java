package com.example.starter.domain;

import java.time.Instant;

/**
 * 弃用窗口事件历史（只追加，不回写）。
 *
 * <p>时间精度为微秒（TIMESTAMP(6)），全部使用 UTC。
 *
 * @param id                 事件 ID
 * @param deprecationId      关联窗口 ID
 * @param artifactId         被弃用制品版本 ID（事件发生时快照）
 * @param name               被弃用制品名称
 * @param version            被弃用制品版本号
 * @param replacementId      替代制品版本 ID（事件发生时快照）
 * @param replacementName    替代制品名称
 * @param replacementVersion 替代制品版本号
 * @param eventType          事件类型：REGISTERED/ADJUSTED/REVOKED
 * @param effectiveAt        事件发生时窗口的生效 UTC 时刻
 * @param note               事件发生时的弃用说明；null 表示无说明
 * @param requestId          触发事件的请求 ID
 * @param eventAt            事件发生 UTC 时刻
 */
public record DeprecationEvent(
        long id,
        long deprecationId,
        long artifactId,
        String name,
        int version,
        long replacementId,
        String replacementName,
        int replacementVersion,
        String eventType,
        Instant effectiveAt,
        String note,
        String requestId,
        Instant eventAt) {
}
