package com.example.starter.api.dto;

/**
 * 单个航点的能量审查明细（审核时冻结）。
 *
 * @param seq               航点序号，从 0 开始
 * @param cumulativeEnergy  至该点的累计主航线耗能（不含备降）
 * @param alternateEnergy   该点备降选项的额外耗能
 * @param requiredEnergy    该点需求能量：累计 + 备降 + 必留余量
 * @param sufficient        需求能量是否不超出发可用能量
 */
public record PointEnergyDto(int seq, long cumulativeEnergy, long alternateEnergy,
                             long requiredEnergy, boolean sufficient) {
}
