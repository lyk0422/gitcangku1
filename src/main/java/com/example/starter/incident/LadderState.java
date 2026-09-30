package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件当前升级阶梯状态，对应 incident_ladder_states 表，每个进入阶梯的事件至多一行。
 * currentLevel 为当前生效等级（1~5，不会因分数下降而自动降低）；
 * lastScore 为最近一次分数更新提交的风险分数；
 * thresholdVersionId 为最近一次分数更新使用的阈值版本；
 * lastPromotedAt 为最近一次自动升级的 UTC 时刻（冷却起点），降级不改变该值，
 * 只有新的升级才能刷新；createdAt/updatedAt 均为 UTC 微秒精度。
 */
public record LadderState(
        long id,
        long incidentId,
        int currentLevel,
        int lastScore,
        long thresholdVersionId,
        Instant lastPromotedAt,
        Instant createdAt,
        Instant updatedAt) {
}
