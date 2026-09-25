package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交一次交接（接棒棒次大于1）。接棒选手由队伍登记的该棒次选手确定。
 *
 * <p>接棒选手 elapsedMillis 必须大于上一棒次该队已记录的累计耗时，否则 422；
 * 交接区用时超过赛事上限判犯规（仍推进），一队同一交接只记一次犯规。
 *
 * @param teamKey         交接队伍标识
 * @param legNo           交接棒次（接棒选手所在棒次，2~棒次数）
 * @param elapsedMillis   接棒选手登记的累计耗时（相对发枪，毫秒），严格递增
 * @param handoffMillis   交接区实际用时（毫秒，1~10000）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SubmitHandoffRequest(
        @NotBlank String teamKey,
        @NotNull @Min(2) @Max(8) Integer legNo,
        @NotNull @Positive Long elapsedMillis,
        @NotNull @Min(1) @Max(10_000) Long handoffMillis,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
