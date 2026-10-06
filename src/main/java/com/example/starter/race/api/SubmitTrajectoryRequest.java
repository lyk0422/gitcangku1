package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 轨迹采样/更正请求：整体提交该选手新的采样点序列（追加或更正），
 * 成功后轨迹版本加一，并使涉及该选手的旧裁定失效。
 *
 * @param samples           新采样点（1~10个；隐含起点固定为(0,0)，时刻严格递增、距离不减）
 * @param trajectoryVersion 客户端所见轨迹版本；首次采样为0
 * @param expectedVersion   客户端所见赛事版本
 * @param requestId         全局唯一请求ID（幂等键）
 * @param operator          操作者，非空
 */
public record SubmitTrajectoryRequest(
        @NotEmpty @Valid List<SamplePoint> samples,
        @NotNull Integer trajectoryVersion,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotBlank String operator
) {

    /**
     * 采样点。
     *
     * @param timeMs     从起跑起的时刻（毫秒，非负整数；因起点固定为(0,0)，实际须为正）
     * @param distanceMm 累计前进距离（毫米，非负整数）
     */
    public record SamplePoint(
            @NotNull Long timeMs,
            @NotNull Long distanceMm
    ) {
    }
}
