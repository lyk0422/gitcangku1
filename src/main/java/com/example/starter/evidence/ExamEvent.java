package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验事件实体，对应 exam_event 表。只追加、不可变，
 * 与封签版本共同构成检验相关的不可变保管链。
 *
 * @param id             主键
 * @param reservationKey 关联预约业务键
 * @param evidenceKey    关联证物业务键
 * @param eventType      事件类型：RESERVED/STARTED/COMPLETED/CANCELLED
 * @param actorId        触发事件的操作人
 * @param sealVersion    关联封签版本：STARTED 为锁定的原版本，COMPLETED 为新版本，其余为 null
 * @param detail         事件说明（完成时为结果摘要，取消时为原因）；null 表示未填写
 * @param createdAt      事件发生时刻（UTC）
 */
public record ExamEvent(
        Long id,
        String reservationKey,
        String evidenceKey,
        String eventType,
        String actorId,
        Integer sealVersion,
        String detail,
        LocalDateTime createdAt) {
}
