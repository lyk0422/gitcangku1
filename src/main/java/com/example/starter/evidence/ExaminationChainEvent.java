package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验保管链事件实体，对应 examination_chain_event 表。事件只追加、不可变，
 * 与预约、开始、完成、取消及封签版本一一对应，是证物离开/回到库房期间的临时链记录。
 *
 * @param id             主键
 * @param reservationKey 关联预约业务键
 * @param evidenceKey    关联证物业务键
 * @param eventType      事件类型：RESERVED/STARTED/COMPLETED/CANCELLED
 * @param actorId        触发事件的操作人
 * @param eventTime      事件发生时刻（UTC）
 * @param startAt        预约开始时刻（UTC，左闭）；完成/取消事件沿用预约时段
 * @param endAt          预约结束时刻（UTC，右开）
 * @param sealVersion    相关封签版本号：STARTED 为锁定的原版本，COMPLETED 为新版本；其余为 NULL
 * @param resultSummary  检验结果摘要；仅 COMPLETED 非空
 * @param detail         事件补充说明；NULL 表示无补充
 */
public record ExaminationChainEvent(
        Long id,
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
