package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合成能量档案。随航线创建/修订登记，绑定生成的航线版本。
 * 段耗能条数必须等于坐标段数（航点数减一，最多 10 段），
 * 备降耗能必须覆盖全部航点；缺项或条数不匹配返回 400。
 *
 * @param departureEnergy   出发可用能量，非负整数
 * @param reserveEnergy     必留余量，非负整数
 * @param segmentEnergies   每段主航线耗能，正整数，按下标对应段序号
 * @param alternateEnergies 每航点备降选项的额外耗能，非负整数，按下标对应航点序号
 */
public record EnergyProfileDto(
        @NotNull @PositiveOrZero Long departureEnergy,
        @NotNull @PositiveOrZero Long reserveEnergy,
        @NotNull @Size(min = 1, max = 10) List<@NotNull @Positive Long> segmentEnergies,
        @NotNull @Size(min = 2, max = 11) List<@NotNull @PositiveOrZero Long> alternateEnergies) {
}
