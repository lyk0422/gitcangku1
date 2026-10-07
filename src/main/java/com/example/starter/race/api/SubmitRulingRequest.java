package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 显式裁定请求：指定追逐对与双方当前轨迹版本，在公共区间内求最早交会时刻。
 * 双方轨迹版本必须与当前一致，否则409；未交会返回422且不产生裁定。
 *
 * @param rulingId                  裁定ID，全局唯一（第二层幂等键）
 * @param pairId                    追逐对ID
 * @param pursuerTrajectoryVersion  追赶者当前轨迹版本
 * @param pursuedTrajectoryVersion  被追者当前轨迹版本
 * @param operator                  操作者，非空
 * @param requestId                 全局唯一请求ID（幂等键）
 */
public record SubmitRulingRequest(
        @NotBlank String rulingId,
        @NotBlank String pairId,
        @NotNull @Positive Integer pursuerTrajectoryVersion,
        @NotNull @Positive Integer pursuedTrajectoryVersion,
        @NotBlank String operator,
        @NotBlank String requestId
) {
}
