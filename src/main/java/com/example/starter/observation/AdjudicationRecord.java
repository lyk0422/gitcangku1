package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 不可变圆弧裁定记录：对应 arc_adjudication 及其冻结分量表的一次裁定完整结果。
 * 冻结原圆弧及全部交集分量，后续修订不倒改旧结果。
 *
 * @param adjudicationId   全局唯一裁定标识
 * @param requestId        生成该裁定的请求标识
 * @param operator         执行裁定的操作者标识
 * @param adjudicatedAtUtc 裁定完成时刻（UTC）
 * @param sources          引用的全部源版本及其原始圆弧（字段身份与源版本不可省）
 * @param components       全部圆弧交集分量，按起始角升序，相接跨零片段已合并
 */
public record AdjudicationRecord(
        String adjudicationId,
        String requestId,
        String operator,
        Instant adjudicatedAtUtc,
        List<AdjudicationSource> sources,
        List<ArcComponent> components) {

    /**
     * 裁定引用源：一条圆弧观测记录的当前版本及其裁定时冻结的原始圆弧分量。
     *
     * @param observationId 引用的圆弧观测记录唯一标识（字段身份）
     * @param version       引用的源版本号
     * @param arcs          裁定时该版本的原始圆弧分量快照，按起始角升序
     */
    public record AdjudicationSource(String observationId, int version, List<ArcComponent> arcs) {
    }
}
