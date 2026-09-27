package com.example.starter.evidence.dto;

import com.example.starter.evidence.ExaminationEventType;

import java.time.LocalDateTime;

/**
 * 检验保管链事件视图（临时链记录，只追加、不可变）。
 *
 * @param reservationKey 关联预约业务键
 * @param evidenceKey    关联证物业务键
 * @param eventType      事件类型：RESERVED/STARTED/COMPLETED/CANCELLED
 * @param actorId        触发事件的操作人
 * @param eventTime      事件发生时刻（UTC）
 * @param startAt        预约开始时刻（UTC，左闭）
 * @param endAt          预约结束时刻（UTC，右开）
 * @param sealVersion    相关封签版本号：STARTED 为锁定的原版本，COMPLETED 为新版本；null 其余
 * @param resultSummary  检验结果摘要；仅 COMPLETED 非空，其余为 null
 * @param detail         事件补充说明；null 表示无补充
 */
public record ExaminationEventView(
        String reservationKey,
        String evidenceKey,
        ExaminationEventType eventType,
        String actorId,
        LocalDateTime eventTime,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Integer sealVersion,
        String resultSummary,
        String detail) {
}
