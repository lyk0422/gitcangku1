package com.example.starter.evidence.dto;

import com.example.starter.evidence.ExamChainEventType;

import java.time.LocalDateTime;

/**
 * 检验保管链事件视图。事件只追加、不可变；封签编号与版本为事件发生时快照。
 *
 * @param reservationKey 关联预约业务键
 * @param evidenceKey    关联证物业务键
 * @param benchKey       事件所在检验台业务键
 * @param eventType      事件类型：RESERVED/STARTED/COMPLETED/CANCELLED
 * @param actorId        事件操作人
 * @param sealNo         事件发生时封签编号快照
 * @param sealVersion    事件发生时封签版本快照
 * @param resultSummary  COMPLETED 事件的检验结果摘要；其他事件为 null
 * @param newSealNo      COMPLETED 事件重封的新封签编号；其他事件为 null
 * @param newSealCheck   COMPLETED 事件的新封签校验值；其他事件为 null
 * @param eventAt        事件发生时刻（UTC）
 * @param createdAt      事件记录写入时间（Asia/Shanghai）
 */
public record ExamChainEventView(
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
