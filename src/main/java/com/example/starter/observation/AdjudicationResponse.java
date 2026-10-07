package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 圆弧裁定响应：返回不可变裁定记录要点、冻结的源圆弧与全部交集分量。
 *
 * @param adjudicationId   全局唯一裁定标识
 * @param requestId        生成该裁定的请求标识
 * @param operator         操作者标识
 * @param adjudicatedAtUtc 裁定完成时刻（UTC，ISO-8601）
 * @param sources          引用的全部源版本及其原始圆弧（字段身份与源版本）
 * @param components       全部圆弧交集分量，按起始角升序，相接跨零片段已合并
 */
public record AdjudicationResponse(
        String adjudicationId,
        String requestId,
        String operator,
        Instant adjudicatedAtUtc,
        List<AdjudicationRecord.AdjudicationSource> sources,
        List<ArcComponent> components) {

    /**
     * 由不可变裁定记录构造响应。
     */
    public static AdjudicationResponse of(AdjudicationRecord record) {
        return new AdjudicationResponse(
                record.adjudicationId(),
                record.requestId(),
                record.operator(),
                record.adjudicatedAtUtc(),
                List.copyOf(record.sources()),
                List.copyOf(record.components()));
    }
}
