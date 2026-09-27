package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验保管链事件实体，对应 exam_chain_event 表。只追加、不可变，
 * 预约、开始、完成、取消以及封签版本变化均以事件形式永久保存。
 *
 * @param id             主键
 * @param reservationKey 关联预约业务键
 * @param evidenceKey    关联证物业务键
 * @param benchKey       事件所在检验台业务键
 * @param eventType      事件类型
 * @param actorId        事件操作人
 * @param sealNo         事件发生时封签编号快照
 * @param sealVersion    事件发生时封签版本快照
 * @param resultSummary  COMPLETED 事件的检验结果摘要；其他事件为 null
 * @param newSealNo      COMPLETED 事件重封的新封签编号；其他事件为 null
 * @param newSealCheck   COMPLETED 事件的新封签校验值；其他事件为 null
 * @param eventAt        事件发生时刻（UTC）
 * @param createdAt      事件记录写入时间（Asia/Shanghai）
 */
public record ExamChainEvent(
        Long id,
        String reservationKey,
        String evidenceKey,
        String benchKey,
        ExamChainEventType eventType,
        String actorId,
        String sealNo,
        int sealVersion,
        String resultSummary,
        String newSealNo,
        String newSealCheck,
        LocalDateTime eventAt,
        LocalDateTime createdAt) {
}
