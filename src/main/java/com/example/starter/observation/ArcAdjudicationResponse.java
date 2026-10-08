package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 圆弧交集裁定响应：返回不可变裁定记录的完整内容（冻结的源圆弧与全部交集分量）。
 *
 * @param adjudicationId 全局唯一裁定标识
 * @param operator       执行裁定的操作者标识
 * @param sources        源圆弧冻结快照（按观测标识升序）
 * @param components     全部交集分量（按起始角升序；允许退化单点，弧长 0）
 * @param createdAtUtc   裁定完成时刻（UTC，ISO-8601）
 */
public record ArcAdjudicationResponse(
        String adjudicationId,
        String operator,
        List<ArcAdjudicationRecord.ArcSource> sources,
        List<CircularArc> components,
        Instant createdAtUtc) {

    /**
     * 由裁定记录构造响应。
     */
    public static ArcAdjudicationResponse of(ArcAdjudicationRecord record) {
        return new ArcAdjudicationResponse(
                record.adjudicationId(),
                record.operator(),
                List.copyOf(record.sources()),
                List.copyOf(record.components()),
                record.createdAtUtc());
    }
}
