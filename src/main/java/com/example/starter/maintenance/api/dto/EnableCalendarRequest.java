package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 启用设备日历档案请求：每台设备仅可启用一次，启用后投用时刻与周期不可修改。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param operator         操作者，非空
 * @param commissionedAt   UTC 投用时刻，启用后不可修改
 * @param periodDays       日历保养周期（天），1 至 365 整数，启用后不可修改
 */
public record EnableCalendarRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operator,
        @NotNull Instant commissionedAt,
        @NotNull @Min(1) @Max(365) Integer periodDays) {
}
