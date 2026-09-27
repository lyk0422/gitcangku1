package com.example.starter.evidence.dto;

import com.example.starter.evidence.ChainEventType;

import java.time.LocalDateTime;

/**
 * 跨案保管链事件视图。事件只追加、不可变；时间统一 UTC，微秒精度。
 *
 * @param eventId     事件主键，按 id 排序即提交顺序
 * @param transferId  所属移交主键
 * @param evidenceKey 证物业务键
 * @param caseKey     事件归属案件（发生时固化，后续操作不改写）
 * @param eventType   事件类型
 * @param actorId     执行该侧动作的保管人
 * @param detail      事件补充说明；null 表示无补充
 * @param eventTime   事件发生时刻（UTC，微秒精度）
 */
public record ChainEventView(
        long eventId,
        long transferId,
        String evidenceKey,
        String caseKey,
        ChainEventType eventType,
        String actorId,
        String detail,
        LocalDateTime eventTime) {
}
