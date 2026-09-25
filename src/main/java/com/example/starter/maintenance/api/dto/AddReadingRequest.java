package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增工时读数请求。允许补录历史读数；新读数初始为 PENDING（草稿，可含任意非负值），
 * 须由不同于录入人的认证人认证后才参与累计工时与保养判定，认证时校验已认证序列单调不减。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param cumulativeMinutes  累计工时（分钟），非负整数
 * @param recordedBy         录入人标识；录入人不得认证自己的读数
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero Long cumulativeMinutes,
        @NotBlank String recordedBy) {
}
