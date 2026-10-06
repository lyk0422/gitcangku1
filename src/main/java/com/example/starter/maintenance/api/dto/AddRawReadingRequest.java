package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 新增原始工时读数请求（仅适用于已启用量程的设备）。
 * 归一累计分钟 = wrapCount * M + raw；先检查 long 溢出，再做前后邻居单调校验；
 * 不根据数值下降猜测回绕，回绕必须显式以 wrapCount 表达。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param readingId        读数标识，设备内唯一
 * @param sampledAt        UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param raw              量程内原始读数（分钟），须满足 0 <= raw < M
 * @param wrapCount        已发生回绕次数，非负整数
 * @param operator         操作者标识（可选，参与幂等身份指纹）
 */
public record AddRawReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero Long raw,
        @NotNull @PositiveOrZero Long wrapCount,
        String operator) {
}
