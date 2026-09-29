package com.example.starter.incident.ladder;

import java.time.Instant;

/**
 * 事件升级阶梯当前态实体，对应 incident_ladder_state 表，每事件至多一行。
 * 无该行表示事件尚未登记风险分数（分数缺失），不允许降级。
 * score 为当前风险分数（非负整数）；currentLevel 为当前等级 1~5；
 * thresholdVersionId 为当前等级裁决所依据的阈值版本（版本行不可变，切换新版本不回写本行，
 * 直到下一次分数更新才按新版本裁决）；
 * lastEscalatedAt/By 为冷却起点：最近一次等级提升（含入阶梯时初始等级即高于 1 级）的 UTC 时刻
 * 与操作人，同级分数更新与降级均不刷新；
 * enteredAt 为首次入阶梯 UTC；updatedAt/By 为最近一次阶梯写入。
 * 所有时间均为 UTC，精度到微秒。
 */
public record IncidentLadder(
        long incidentId,
        long score,
        int currentLevel,
        long thresholdVersionId,
        Instant lastEscalatedAt,
        String lastEscalatedBy,
        Instant enteredAt,
        Instant updatedAt,
        String updatedBy) {
}
