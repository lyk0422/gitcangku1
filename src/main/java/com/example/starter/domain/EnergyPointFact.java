package com.example.starter.domain;

/**
 * 单个航点的能量核算事实（审核时冻结，不可变）。
 *
 * @param pointIndex            航点序号，从 0 开始（0 为出发点）
 * @param cumulativeConsumption 至该点的主航线累计耗能（不含该点备降），单位标准能量
 * @param diversionEnergy       该点备降选项的额外耗能，非负
 * @param required              该点总需求：累计耗能 + 备降耗能 + 必留余量
 * @param deficit               缺口：required - 出发可用能量，足够时为 0
 */
public record EnergyPointFact(int pointIndex, long cumulativeConsumption, long diversionEnergy,
                              long required, long deficit) {

    /** 该点能量是否足够（缺口为 0）。 */
    public boolean sufficient() {
        return deficit == 0;
    }
}
