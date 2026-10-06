package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增工时读数请求。允许补录历史读数，但按采样时刻排序后归一累计分钟须单调不减。
 * 已启用量程的设备必须使用 raw/wrapCount 原始读数（直接累计值接口返回 422）；
 * 未启用量程的设备保持旧契约，仅使用 cumulativeMinutes。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param operator           操作者标识（可选，计入幂等身份）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param cumulativeMinutes  累计工时（分钟），非负整数；仅未启用量程设备可用
 * @param raw                量程内原始读数，0 <= raw < M；仅已启用量程设备可用
 * @param wrapCount          回绕次数，非负整数；仅已启用量程设备可用
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        String operator,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @PositiveOrZero Long cumulativeMinutes,
        @PositiveOrZero Long raw,
        @PositiveOrZero Long wrapCount) {
}
