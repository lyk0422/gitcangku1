package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 修订读数请求：只改数值、不改 UTC 采样时刻；作为历史保养锚点的读数不可修订（409）。
 * 已启用量程的设备只能改 raw 和 wrapCount，并须携带当前 readingRevision；
 * 未启用量程的设备保持旧契约，仅改 cumulativeMinutes。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param operator           操作者标识（可选，计入幂等身份）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingRevision    读数当前修订号（原始修订必填），不一致时返回 409
 * @param cumulativeMinutes  修订后的累计工时（分钟）；仅未启用量程设备可用
 * @param raw                修订后的量程内原始读数，0 <= raw < M；仅已启用量程设备可用
 * @param wrapCount          修订后的回绕次数，非负整数；仅已启用量程设备可用
 */
public record ReviseReadingRequest(
        @NotBlank String requestId,
        String operator,
        @NotNull Long expectedVersion,
        @PositiveOrZero Long readingRevision,
        @PositiveOrZero Long cumulativeMinutes,
        @PositiveOrZero Long raw,
        @PositiveOrZero Long wrapCount) {
}
