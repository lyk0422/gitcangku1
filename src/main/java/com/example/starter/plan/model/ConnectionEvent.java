package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 接续历史事件（只追加不改写）。null 语义稳定：
 * CANCELLED 事件的时刻与分钟数字段均为 null；windowStartUtc/windowEndUtc 仅 RISK 事件非空。
 *
 * @param id                 主键，单调递增即事件顺序
 * @param connectionId       所属接续 id
 * @param eventType          事件类型
 * @param predecessorPlanId  判定所依据的前车计划 id
 * @param successorPlanId    判定所依据的后车计划 id
 * @param predecessorVersion 判定所依据的前车版本
 * @param oldTimeUtc         原时刻：RISK 为前车原终到，RESCHEDULED 为后车原始发，REGISTERED 为登记时前车终到
 * @param newTimeUtc         新时刻：RISK 为前车新终到，RESCHEDULED 为后车新始发，REGISTERED 为登记时后车始发
 * @param requiredMinutes    要求保障分钟数
 * @param actualMinutes      实际接续分钟数（后车始发减前车终到）
 * @param shortfallMinutes   保障差额（required - actual），正数为不足
 * @param windowStartUtc     可选改签时间窗起点（后车最早始发，含），仅 RISK 事件非空
 * @param windowEndUtc       可选改签时间窗终点（后车最晚始发，含），仅 RISK 事件非空
 * @param createdAt          事件记录时刻，UTC 毫秒
 */
public record ConnectionEvent(long id, long connectionId, ConnectionEventType eventType,
                              long predecessorPlanId, long successorPlanId, int predecessorVersion,
                              Instant oldTimeUtc, Instant newTimeUtc,
                              Integer requiredMinutes, Integer actualMinutes, Integer shortfallMinutes,
                              Instant windowStartUtc, Instant windowEndUtc, long createdAt) {
}