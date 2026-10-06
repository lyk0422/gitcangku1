package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次审核的能量评估结果（随审核记录冻结，不可变）。
 *
 * <p>判定规则：至每点的累计主航线耗能 + 该点备降耗能 + 必留余量 ≤ 出发可用能量；
 * 任一点不足即整体能量不合格。所有数值为长整型精确求和，输入已限制在
 * 有界范围（单项 ≤ 1e12，最多 10 段），累加不会溢出。</p>
 *
 * @param availableEnergy 出发可用能量，非负
 * @param reserveEnergy   必留余量，非负，每个航点判定都计入
 * @param points          按航点顺序的逐点核算事实
 */
public record EnergyAssessment(long availableEnergy, long reserveEnergy,
                               List<EnergyPointFact> points) {

    public EnergyAssessment {
        points = List.copyOf(points);
    }

    /**
     * 按登记的能量档案逐点核算。
     *
     * @param availableEnergy 出发可用能量
     * @param reserveEnergy   必留余量
     * @param segmentCosts    每段正整数耗能，条数等于坐标段数（航点数 - 1）
     * @param diversionCosts  每个航点的备降额外耗能，条数等于航点数
     */
    public static EnergyAssessment assess(long availableEnergy, long reserveEnergy,
                                          List<Long> segmentCosts, List<Long> diversionCosts) {
        List<EnergyPointFact> facts = new ArrayList<>(diversionCosts.size());
        long cumulative = 0;
        for (int i = 0; i < diversionCosts.size(); i++) {
            if (i > 0) {
                cumulative += segmentCosts.get(i - 1);
            }
            long required = cumulative + diversionCosts.get(i) + reserveEnergy;
            long deficit = Math.max(0, required - availableEnergy);
            facts.add(new EnergyPointFact(i, cumulative, diversionCosts.get(i), required, deficit));
        }
        return new EnergyAssessment(availableEnergy, reserveEnergy, facts);
    }

    /** 是否存在能量不足的航点。 */
    public boolean hasBlockedPoints() {
        for (EnergyPointFact fact : points) {
            if (!fact.sufficient()) {
                return true;
            }
        }
        return false;
    }

    /** 全部能量不足的航点事实（按点号升序）。 */
    public List<EnergyPointFact> blockedPoints() {
        List<EnergyPointFact> blocked = new ArrayList<>();
        for (EnergyPointFact fact : points) {
            if (!fact.sufficient()) {
                blocked.add(fact);
            }
        }
        return blocked;
    }
}
