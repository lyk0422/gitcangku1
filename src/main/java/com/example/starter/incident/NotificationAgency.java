package com.example.starter.incident;

import java.time.Instant;

/**
 * 通报机构行实体，对应 incident_notification_agencies 表。
 * critical 为是否关键机构：关键机构逾期未回执阻止事件降级，普通机构逾期只进待跟进清单。
 * ackDeadline 为 UTC 回执截止时刻：当前时刻（注入时钟）≥ 该时刻即逾期，恰好到期视为逾期。
 * 排序以 id（创建请求顺序）为准，保证机构排序稳定。
 */
public record NotificationAgency(
        long id,
        long notificationId,
        long incidentId,
        String agencyCode,
        boolean critical,
        Instant ackDeadline,
        Instant createdAt) {
}
