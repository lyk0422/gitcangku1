package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 弃用窗口只追加历史事件视图；字段、排序与时间精度永久稳定。
 *
 * @param action REGISTERED=登记，REVOKED=撤销（撤销事件保留撤销前的替代与说明证据）
 */
public record DeprecationEventView(
        long id,
        long windowId,
        String action,
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt,
        String note,
        String requestId,
        Instant createdAt) {
}
