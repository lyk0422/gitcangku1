package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 追逐对裁定请求：显式指定双方当前轨迹版本，
 * 在双方轨迹公共区间内找最早位置差达到0的时刻并精确保存。
 *
 * @param pairNo                  追逐对序号
 * @param chaserTrajectoryVersion 客户端所见追赶者轨迹版本
 * @param chasedTrajectoryVersion 客户端所见被追者轨迹版本
 * @param expectedVersion         客户端所见赛事版本
 * @param requestId               全局唯一请求ID（幂等键）
 * @param operator                操作者，非空
 */
public record AdjudicatePursuitRequest(
        @NotNull Integer pairNo,
        @NotNull Integer chaserTrajectoryVersion,
        @NotNull Integer chasedTrajectoryVersion,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotBlank String operator
) {
}
