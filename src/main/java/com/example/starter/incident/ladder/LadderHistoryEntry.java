package com.example.starter.incident.ladder;

import java.time.Instant;

/**
 * 升级阶梯等级历史行实体，对应 incident_ladder_history 表。
 * 行一旦写入永久不可变：后续阈值版本修订、证据更正都不回写历史；
 * fromLevel/fromScore 仅 INIT 行为空，其余均非空；toLevel/toScore 始终非空；
 * thresholdVersionKey 为裁决所用阈值版本的业务键快照；
 * cooldownStartAt 为该行落库后该事件的冷却起点（升级行刷新为 occurredAt，
 * 同级更新与降级行继承当时的最近升级时刻，永不为空——入阶梯即设置基准）；
 * commandKey 为触发该行写入的命令幂等键（同一批量分数更新的多行共享同一个键），便于诊断；
 * occurredAt 为变更生效 UTC 时刻（微秒精度）。排序固定按 id（落库顺序）。
 */
public record LadderHistoryEntry(
        long id,
        long incidentId,
        LadderChangeType changeType,
        Integer fromLevel,
        int toLevel,
        Long fromScore,
        long toScore,
        long thresholdVersionId,
        String thresholdVersionKey,
        String actor,
        Instant cooldownStartAt,
        String commandKey,
        Instant occurredAt) {
}
