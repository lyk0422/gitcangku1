package com.example.starter.domain;

import java.util.List;

/**
 * 航线版本的合成能量档案（不可变）。
 *
 * <p>段耗能条数等于坐标段数（航点数减一，最多 10 段），备降耗能覆盖全部航点。
 * 所有能量为非负长整型（段耗能为正整数），单位一致，求和使用精确溢出检查。</p>
 *
 * @param departureEnergy   出发可用能量，非负
 * @param reserveEnergy     必留余量，非负
 * @param segmentEnergies   每段主航线耗能，正整数，按下标对应段序号
 * @param alternateEnergies 每航点备降选项的额外耗能，非负，按下标对应航点序号
 */
public record EnergyProfile(long departureEnergy, long reserveEnergy,
                            List<Long> segmentEnergies, List<Long> alternateEnergies) {
}
