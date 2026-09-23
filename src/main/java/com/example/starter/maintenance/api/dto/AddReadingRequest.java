package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增工时表读数请求。读数登记到设备当前 ACTIVE 工时表；允许补录历史，
 * 同一工时表内按采样时刻排序后原始工时须单调不减，且不得小于该表 initialRawHours。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param readingId        读数标识，设备内唯一（跨工时表也不可重复）
 * @param sampledAt        UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param rawHours         表内原始工时读数（小时），非负
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero BigDecimal rawHours) {
}
