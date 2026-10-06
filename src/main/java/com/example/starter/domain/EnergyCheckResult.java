package com.example.starter.domain;

import java.util.List;

/**
 * 一次能量审查的完整结果（不可变快照，随审核记录冻结）。
 *
 * @param departureEnergy 出发可用能量
 * @param reserveEnergy   必留余量
 * @param points          每航点能量明细（含累计与需求）
 * @param blockedPointSeq 首个能量不足的航点序号；全部合格为 null
 * @param deficit         首个不足点的缺口（需求能量减出发可用能量）；全部合格为 null
 */
public record EnergyCheckResult(long departureEnergy, long reserveEnergy,
                                List<PointEnergy> points,
                                Integer blockedPointSeq, Long deficit) {

    /** 全部航点能量是否合格。 */
    public boolean sufficient() {
        return blockedPointSeq == null;
    }
}
