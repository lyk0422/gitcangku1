package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 批次不可变事件流水的一行。事件只追加，按 id 升序即提交顺序；
 * payload 为封存内容快照（JSON 文本），字段、键顺序与 null 含义保持稳定。
 *
 * @param id         事件序号（自增）
 * @param batchId    所属批次 ID
 * @param revisionNo 事件关联的测量修订版本号
 * @param eventType  事件类型，见 {@link BatchEventType}
 * @param actor      事件触发角色
 * @param payload    封存内容快照（JSON）
 * @param occurredAt 事件发生时间（UTC，微秒精度）
 */
public record BatchEvent(
        long id,
        long batchId,
        int revisionNo,
        String eventType,
        String actor,
        String payload,
        Instant occurredAt) {
}
