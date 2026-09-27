package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 新增工时读数请求。允许补录历史读数，但按采样时刻排序后累计分钟须单调不减。
 * 设备存在生效能源隔离令时本请求返回 409 且读数不落库（证据另行固化）；保养检查不受影响。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param cumulativeMinutes  累计工时（分钟），非负整数
 * @param operatorId         提交操作人；被隔离拒绝时随证据固化，可为空（兼容旧调用方）
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero Long cumulativeMinutes,
        @Size(max = 64) String operatorId) {
}
