package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.example.starter.maintenance.domain.LoadType;

/**
 * 新增工时读数请求。允许补录历史读数，但按采样时刻排序后累计分钟须单调不减。
 * 每条新读数必须声明负荷类型：本读数与前一条读数之间的区间增量按该权重计入等效累计。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param operator           操作者标识（可选，纳入幂等身份）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻；同设备同一时刻仅允许一条读数
 * @param cumulativeMinutes  累计工时（分钟），非负整数
 * @param loadType           负荷类型：NORMAL（权重 1）或 HEAVY（权重 2）
 */
public record AddReadingRequest(
        @NotBlank String requestId,
        String operator,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull Instant sampledAt,
        @NotNull @PositiveOrZero Long cumulativeMinutes,
        @NotNull LoadType loadType) {
}
