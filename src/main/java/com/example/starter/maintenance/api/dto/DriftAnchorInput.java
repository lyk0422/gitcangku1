package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 漂移修正锚点输入。锚点按读数采样时刻规范化排序，提交顺序不影响等价性。
 *
 * @param readingId        锚点读数标识，须已存在且未被保养记录冻结
 * @param expectedVersion  锚点读数的期望修订号，激活时与当前修订号不一致则整单 409
 * @param cumulativeHours  经校准的真实累计工时（小时），非负，最多 3 位小数（精确到 0.001 小时）
 */
public record DriftAnchorInput(
        @NotBlank String readingId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 3) BigDecimal cumulativeHours) {
}
