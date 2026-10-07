package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交/更正选手轨迹采样请求：整体替换该选手当前采样集合并推进轨迹版本，
 * 成功后该选手涉及的旧裁定失效。采样为从起跑起的非负整数毫秒与累计前进毫米，
 * 首点固定(0,0)，时刻严格递增、距离单调不减，最多10点。
 *
 * @param expectedVersion           客户端所见赛事版本
 * @param expectedTrajectoryVersion 客户端所见轨迹版本；0 表示该选手尚无轨迹
 * @param samples                   完整采样点集合（1~10个，首点必须为(0,0)）
 * @param operator                  操作者，非空
 * @param requestId                 全局唯一请求ID（幂等键）
 */
public record SubmitSamplesRequest(
        @NotNull Integer expectedVersion,
        @NotNull @PositiveOrZero Integer expectedTrajectoryVersion,
        @NotNull @Size(min = 1, max = 10) List<@Valid SamplePointRequest> samples,
        @NotBlank String operator,
        @NotBlank String requestId
) {

    /**
     * 轨迹采样点。
     *
     * @param elapsedMs  从起跑起的累计毫秒（非负整数）
     * @param progressMm 累计前进毫米（非负整数）
     */
    public record SamplePointRequest(
            @NotNull @PositiveOrZero Long elapsedMs,
            @NotNull @PositiveOrZero Long progressMm
    ) {
    }
}
