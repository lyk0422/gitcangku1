package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增工时读数请求。允许补录历史读数，但按采样时刻排序后累计分钟须单调不减。
 * 新增读数必须声明负荷权重档位，用于等效寿命加权累计。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param cumulativeMinutes  累计工时（分钟），非负整数
 * @param loadWeight         负荷权重档位：NORMAL（权重 1）或 HEAVY（权重 2）
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero Long cumulativeMinutes,
        @NotBlank @Pattern(regexp = "NORMAL|HEAVY", message = "须为 NORMAL 或 HEAVY") String loadWeight) {
}
