package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核时冻结的能量评估快照；航线版本无能量档案时为 null。
 *
 * @param availableEnergy 出发可用能量
 * @param reserveEnergy   必留余量
 * @param points          按航点顺序的逐点核算事实
 */
public record EnergySnapshotDto(long availableEnergy, long reserveEnergy,
                                List<EnergyPointFactDto> points) {
}
