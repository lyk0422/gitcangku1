package com.example.starter.api.dto;

import java.util.List;

/**
 * 能量审查结果（审核时冻结的不可变快照）。
 *
 * @param departureEnergy 出发可用能量
 * @param reserveEnergy   必留余量
 * @param points          每航点能量明细（含累计与需求）
 * @param sufficient      全部航点能量是否合格
 * @param blockedPointSeq 首个能量不足的航点序号；全部合格为 null
 * @param deficit         首个不足点的缺口（需求能量减出发可用能量）；全部合格为 null
 */
public record EnergyCheckDto(long departureEnergy, long reserveEnergy,
                             List<PointEnergyDto> points, boolean sufficient,
                             Integer blockedPointSeq, Long deficit) {
}
