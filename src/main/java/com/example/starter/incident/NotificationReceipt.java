package com.example.starter.incident;

import java.time.Instant;

/**
 * 通报回执实体，对应 incident_notification_receipts 表。
 * agencyId 唯一保证同一机构只落首份有效回执，后来重复报文不增加完成数也不覆盖原时间；
 * requestId 全局唯一用于报文幂等：同键原报文重放原响应，换机构或内容返回 409。
 * receivedAt 为服务端收到首份有效回执的 UTC 时刻（注入时钟）。
 */
public record NotificationReceipt(
        long id,
        long agencyId,
        long incidentId,
        String agencyCode,
        String requestId,
        String signer,
        String content,
        Instant receivedAt,
        Instant createdAt) {
}
