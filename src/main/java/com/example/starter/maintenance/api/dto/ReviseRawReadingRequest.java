package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 原始读数修订请求（仅适用于已启用量程的设备）：只能改 raw 与 wrapCount，
 * 不改 UTC 采样时刻；按所有有效相邻读数重新判断单调性；历史保养锚点不可修订（409）。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param readingRevision  读数当前修订号，与当前修订号不一致时返回 409
 * @param raw              修订后的量程内原始读数（分钟），须满足 0 <= raw < M
 * @param wrapCount        修订后的回绕次数，非负整数
 * @param operator         操作者标识（可选，参与幂等身份指纹）
 */
public record ReviseRawReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @Positive Integer readingRevision,
        @NotNull @PositiveOrZero Long raw,
        @NotNull @PositiveOrZero Long wrapCount,
        String operator) {
}
