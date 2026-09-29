package com.example.starter.incident;

import java.time.Instant;

/**
 * 外部机构回执原始报文实体，对应 incident_notification_acks 表；request_id 全局唯一。
 * 同一 requestId 重放首次响应，换机构或内容返回 409（由 command_keys 幂等约束保证，
 * 该表唯一约束兜底并发）。是否计入完成数以 incident_notification_agencies 的
 * 条件更新（received_at IS NULL）为准，首份之外的重复报文不写入本表、不覆盖首份结果。
 */
public record NotificationReceipt(
        long id,
        long agencyNotificationId,
        String requestId,
        String signer,
        String content,
        Instant receivedAt,
        Instant createdAt) {
}
