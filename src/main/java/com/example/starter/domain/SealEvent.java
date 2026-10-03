package com.example.starter.domain;

import java.time.Instant;

/**
 * 封签状态记录：只追加不删除，破损只能追加异常记录。
 *
 * @param id 自增主键
 * @param packageId 所属封存包
 * @param eventType 事件类型：HANDED_OVER 交接 / DAMAGED 封签破损 / SUPERSEDED 被取代 / RESEAL_CREATED 重新封存建包
 * @param sealNo 事件关联的封签编号，null 表示无
 * @param note 事件说明，null 表示无
 * @param recordedAt 记录时刻（UTC）
 */
public record SealEvent(long id, long packageId, String eventType, String sealNo, String note, Instant recordedAt) {
}