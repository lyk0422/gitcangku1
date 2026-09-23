package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 工时表更换请求：登记 replacementKey 时关闭当前 ACTIVE 表并启用新表。
 *
 * @param replacementKey         更换请求唯一标识（幂等键），全局唯一
 * @param expectedVersion        设备期望版本号，与当前版本不一致时返回 409
 * @param newMeterKey            新工时表唯一标识，全局唯一
 * @param finalRawHours          旧表最终原始读数（小时），非负且不得小于旧表最后有效读数
 * @param initialRawHours        新表初始原始读数（小时），非负
 * @param lastReadingRevisionNo  旧表当前最后一条读数的修订版本（乐观令牌）；旧表无读数时为 null，
 *                               与当前版本不一致时返回 409，防止与旧表修订并发产生半重算
 */
public record ReplaceMeterRequest(
        @NotBlank String replacementKey,
        @NotNull Long expectedVersion,
        @NotBlank String newMeterKey,
        @NotNull @PositiveOrZero BigDecimal finalRawHours,
        @NotNull @PositiveOrZero BigDecimal initialRawHours,
        @Positive Integer lastReadingRevisionNo) {
}
