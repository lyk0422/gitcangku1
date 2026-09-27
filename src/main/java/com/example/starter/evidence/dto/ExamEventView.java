package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 检验事件视图（不可变保管链的一环）。
 *
 * @param reservationKey 关联预约业务键
 * @param evidenceKey    关联证物业务键
 * @param eventType      事件类型：RESERVED/STARTED/COMPLETED/CANCELLED
 * @param actorId        触发事件的操作人
 * @param sealVersion    关联封签版本：STARTED 为锁定的原版本，COMPLETED 为新版本，其余为 null
 * @param detail         事件说明；null 表示未填写
 * @param createdAt      事件发生时刻（UTC）
 */
public record ExamEventView(
        String reservationKey,
        String evidenceKey,
        String eventType,
        String actorId,
        Integer sealVersion,
        String detail,
        LocalDateTime createdAt) {
}
