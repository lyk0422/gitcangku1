package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 能量审查计算：对每个航点校验 累计主航线耗能 + 该点备降耗能 + 必留余量
 * 不超出发可用能量。求和使用 Math.addExact 精确计算；档案在登记时已做
 * 溢出校验，因此审查时不会溢出。
 */
public final class EnergyEvaluator {

    private EnergyEvaluator() {
    }

    /**
     * 评估能量档案，返回每点明细与首个不足点（如有）。
     *
     * @param profile 已登记的能量档案（段数与航点数已在登记时校验一致）
     * @return 审查结果；首个不足点给出点号（从 0 开始）与缺口
     */
    public static EnergyCheckResult evaluate(EnergyProfile profile) {
        int pointCount = profile.alternateEnergies().size();
        List<PointEnergy> points = new ArrayList<>(pointCount);
        long cumulative = 0L;
        for (int i = 0; i < pointCount; i++) {
            if (i > 0) {
                cumulative = Math.addExact(cumulative, profile.segmentEnergies().get(i - 1));
            }
            long required = Math.addExact(
                    Math.addExact(cumulative, profile.alternateEnergies().get(i)),
                    profile.reserveEnergy());
            points.add(new PointEnergy(i, cumulative, profile.alternateEnergies().get(i),
                    required, required <= profile.departureEnergy()));
        }
        for (PointEnergy point : points) {
            if (!point.sufficient()) {
                return new EnergyCheckResult(profile.departureEnergy(), profile.reserveEnergy(),
                        List.copyOf(points), point.seq(),
                        point.requiredEnergy() - profile.departureEnergy());
            }
        }
        return new EnergyCheckResult(profile.departureEnergy(), profile.reserveEnergy(),
                List.copyOf(points), null, null);
    }
}
