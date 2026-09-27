package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案保管链事件实体，对应 custody_chain_event 表。记录只追加、不可变，
 * 按 id 排序即提交顺序；事件案件归属为发生时固化值，后续操作不得改写。
 *
 * @param id          主键
 * @param transferId  所属移交主键（transferId）
 * @param evidenceKey 证物业务键
 * @param caseKey     事件归属案件
 * @param eventType   事件类型
 * @param actorId     执行该侧动作的保管人
 * @param detail      事件补充说明；null 表示无补充
 * @param eventTime   事件发生时刻（UTC，微秒精度）
 */
public record CustodyChainEvent(
        Long id,
        Long transferId,
        String evidenceKey,
        String caseKey,
        ChainEventType eventType,
        String actorId,
        String detail,
        LocalDateTime eventTime) {
}
