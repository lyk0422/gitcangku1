package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 接续风险判定证据（不可变历史）。前车改签延后时同事务追加；
 * 后续取消接续或再次改签不改写。所有时刻 UTC，区间左闭右开。
 *
 * @param id                            主键
 * @param connectionId                  触发风险的接续登记 id
 * @param predecessorPlanId             判定时前车（改签后生效计划）id
 * @param successorPlanId               被标记风险的后车计划 id
 * @param predecessorOldPlanId          改签中被取消的前车旧计划 id
 * @param predecessorNewPlanId          改签后发布的前车新计划 id
 * @param predecessorBaseVersion        改签所依据的前车旧版本
 * @param predecessorRescheduledVersion 改签后前车新版本
 * @param oldPredArrivalUtc             改签前前车终到（原时刻证据）
 * @param newPredArrivalUtc             改签后前车终到（新时刻证据）
 * @param oldSuccessorDepartureUtc      判定时后车始发（原时刻证据）
 * @param newSuccessorDepartureUtc      建议后车新始发；无可用窗为 null
 * @param guaranteeMinutes              接续要求保障分钟数
 * @param requiredGapMinutes            要求间隔分钟数
 * @param actualGapMinutes              改签后实际间隔分钟数（可为负）
 * @param shortfallMinutes              保障差额（要求-实际，不足为正）
 * @param candidateWindowStartUtc       可选改签时间窗开始（含）；无窗为 null
 * @param candidateWindowEndUtc         可选改签时间窗结束（不含）；无窗为 null
 * @param candidateAvailable            是否存在可用候选改签时间窗
 * @param successorStarted              判定时后车是否已开始（已开始不自动标记候选改签）
 * @param createdAt                     记录生成时刻，UTC 毫秒
 */
public record ConnectionRisk(long id, long connectionId, long predecessorPlanId, long successorPlanId,
                             long predecessorOldPlanId, long predecessorNewPlanId,
                             int predecessorBaseVersion, int predecessorRescheduledVersion,
                             Instant oldPredArrivalUtc, Instant newPredArrivalUtc,
                             Instant oldSuccessorDepartureUtc, Instant newSuccessorDepartureUtc,
                             int guaranteeMinutes, int requiredGapMinutes, long actualGapMinutes,
                             long shortfallMinutes, Instant candidateWindowStartUtc,
                             Instant candidateWindowEndUtc, boolean candidateAvailable,
                             boolean successorStarted, long createdAt) {
}
