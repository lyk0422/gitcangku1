package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增闭合应力循环请求。仅完整闭合循环（升峰后回落到基准应力）写入损伤；
 * 缺回落、时刻越界、区间重叠均拒绝（422），不写半循环。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param cycleId          循环标识，设备内唯一
 * @param fromReadingId    起始引用工时读数标识（引用后该读数不可修订）
 * @param fromRevisionNo   起始引用读数的当前修订号，不一致返回 409
 * @param toReadingId      结束引用工时读数标识（引用后该读数不可修订）
 * @param toRevisionNo     结束引用读数的当前修订号，不一致返回 409
 * @param peakAt           升峰采样时刻（UTC），须不早于起始读数时刻且严格早于回落时刻
 * @param returnAt         回落采样时刻（UTC），须不晚于结束读数时刻
 * @param peakStressMpa    峰值应力（兆帕），须大于基准应力
 * @param returnStressMpa  回落应力（兆帕），须等于基准应力
 * @param operator         操作者，非空
 */
public record AddCycleRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String cycleId,
        @NotBlank String fromReadingId,
        @NotNull @Positive Integer fromRevisionNo,
        @NotBlank String toReadingId,
        @NotNull @Positive Integer toRevisionNo,
        @NotNull Instant peakAt,
        @NotNull Instant returnAt,
        @NotNull @PositiveOrZero Long peakStressMpa,
        @NotNull @PositiveOrZero Long returnStressMpa,
        @NotBlank String operator) {
}
