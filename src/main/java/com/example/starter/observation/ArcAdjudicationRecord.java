package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 不可变圆弧交集裁定记录：对应 arc_adjudication 表的一行，成功裁定后原子落库，永不修改。
 * 冻结参与裁定的源圆弧与全部交集分量，后续对观测的修订不倒改旧裁定结果。
 *
 * @param adjudicationId 全局唯一裁定标识
 * @param requestId      生成该记录的请求标识
 * @param operator       执行裁定的操作者标识
 * @param sources        源圆弧冻结快照（按观测标识升序）
 * @param components     全部交集分量（按起始角升序；允许退化单点，弧长 0）
 * @param createdAtUtc   裁定完成时刻（UTC）
 */
public record ArcAdjudicationRecord(
        String adjudicationId,
        String requestId,
        String operator,
        List<ArcSource> sources,
        List<CircularArc> components,
        Instant createdAtUtc) {

    /**
     * 裁定的源圆弧快照：观测标识、裁定时的当前版本与当时的完整圆弧字段组。
     *
     * @param observationId 观测记录唯一标识
     * @param version       裁定时核对的当前版本号
     * @param startAngle    起始角，单位千分之一度，0..359999
     * @param arcLength     正向弧长，单位千分之一度，1..180000
     */
    public record ArcSource(
            String observationId,
            int version,
            int startAngle,
            int arcLength) {
    }
}
