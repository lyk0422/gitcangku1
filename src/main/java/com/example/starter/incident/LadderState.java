package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件升级阶梯当前状态实体，对应 incident_ladder_states 表，每事件至多一条。
 * currentLevel 为当前等级 0~5（0 表示未达 1 级阈值）；
 * cooldownStartAt 为冷却起点 UTC：最近一次等级变更（升级或降级）时刻，尚无变更时为首次评分时刻；
 * lastOperator 为最近一次等级变更操作人，降级必须由与其不同的指挥员确认。
 */
public record LadderState(
        long id,
        long incidentId,
        int riskScore,
        int currentLevel,
        int configVersion,
        Instant cooldownStartAt,
        String lastOperator,
        Instant createdAt,
        Instant updatedAt) {
}
