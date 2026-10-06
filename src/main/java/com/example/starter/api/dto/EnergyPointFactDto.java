package com.example.starter.api.dto;

/**
 * 审核结果中单个航点的能量核算事实（冻结快照）。
 *
 * @param pointIndex            航点序号，从 0 开始
 * @param cumulativeConsumption 至该点的主航线累计耗能
 * @param diversionEnergy       该点备降选项的额外耗能
 * @param required              该点总需求：累计耗能 + 备降耗能 + 必留余量
 * @param deficit               缺口：required - 出发可用能量，足够时为 0
 */
public record EnergyPointFactDto(int pointIndex, long cumulativeConsumption, long diversionEnergy,
                                 long required, long deficit) {
}
