package com.example.starter.incident;

import java.time.Instant;

/**
 * 通报机构行实体，对应 incident_notification_agencies 表，(通报 id, 机构键) 唯一。
 * critical=true 表示关键机构：截止时刻（UTC）仍无有效回执时阻止事件降级；
 * 普通机构逾期只进入待跟进清单。
 * firstRequestId/signer/receivedAt 仅记录该机构<strong>首份</strong>有效回执：
 * 后来的重复报文不覆盖首份时间、签收人，也不增加完成数；无回执时三列为空。
 * ackDeadline 为 UTC 回执截止时刻，当前时刻 ≥ 截止时刻且无回执即视为逾期（恰好到期算逾期）。
 */
public record AgencyNotification(
        long id,
        long notificationId,
        String agencyKey,
        boolean critical,
        Instant ackDeadline,
        String firstRequestId,
        String signer,
        Instant receivedAt,
        Instant createdAt,
        Instant updatedAt) {
}
