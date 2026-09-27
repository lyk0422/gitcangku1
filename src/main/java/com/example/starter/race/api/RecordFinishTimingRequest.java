package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 终点（完赛）计时事件登记请求（不可变证据）。
 * occurredAt 为事件发生的绝对 UTC 时刻，用于医疗暂停区间裁决。
 *
 * @param finishTimingId  终点计时事件ID，全局唯一
 * @param occurredAt      事件发生绝对UTC毫秒时间戳
 * @param finishTimeMs    原始完赛耗时（毫秒，1~86400000）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RecordFinishTimingRequest(
        @NotBlank String finishTimingId,
        @NotNull Long occurredAt,
        @NotNull @Positive Long finishTimeMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
