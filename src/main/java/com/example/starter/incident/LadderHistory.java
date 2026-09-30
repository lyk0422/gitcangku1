package com.example.starter.incident;

import java.time.Instant;

/**
 * 升级阶梯等级变更历史，对应 incident_ladder_history 表，只追加、不可变。
 * 自动升级与人工降级各落一条；fromLevel 为 null 表示事件首次进入阶梯，
 * 其余为变更前等级；toLevel 为变更后等级；PROMOTE 可跨多级，DEMOTE 必须逐级。
 * score 为触发本次变更时提交的风险分数；cooldownSince 为本次变更后冷却起点
 * （始终取最近一次升级时刻，降级不刷新）；operator 为操作人——自动升级为分数提交人，
 * 降级为确认降级的指挥员；thresholdVersionId 为判定使用的阈值版本。时间均为 UTC。
 */
public record LadderHistory(
        long id,
        long incidentId,
        LadderChangeType changeType,
        Integer fromLevel,
        int toLevel,
        Integer score,
        Long thresholdVersionId,
        String operator,
        Instant cooldownSince,
        Instant occurredAt) {
}
