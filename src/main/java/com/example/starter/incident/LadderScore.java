package com.example.starter.incident;

import java.time.Instant;

/**
 * 风险分数不可变证据，对应 incident_ladder_scores 表，每次分数更新提交一条（整批每事件一条）。
 * 提交后任何字段不可修改、不可删除；后续阈值版本修订与等级变更均不回写旧证据。
 * score 为本次提交的风险分数（无单位的整数分值）；thresholdVersionId 为本次评估使用的阈值版本；
 * evaluatedLevel 为按该版本阈值与左闭右开区间算出的分数对应等级，未进入阶梯时为 null；
 * submittedBy 为提交人（X-Actor-Id）；occurredAt 为提交 UTC 时刻。
 */
public record LadderScore(
        long id,
        long incidentId,
        int score,
        long thresholdVersionId,
        Integer evaluatedLevel,
        String submittedBy,
        Instant occurredAt,
        Instant createdAt) {
}
