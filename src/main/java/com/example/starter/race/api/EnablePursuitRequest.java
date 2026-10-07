package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 启用追逐赛（PURSUIT）模式请求：仅允许在计时前（无任何分段/轨迹数据）执行一次，
 * 固定 1~5 个追逐对及每人非负整数起始距离（毫米），启用后不可回退。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param pairs           追逐对声明（1~5对，pairId 赛事内唯一）
 * @param operator        操作者，非空
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record EnablePursuitRequest(
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 5) List<@Valid PursuitPairDefinition> pairs,
        @NotBlank String operator,
        @NotBlank String requestId
) {

    /**
     * 追逐对声明。
     *
     * @param pairId         追逐对ID，赛事内唯一
     * @param pursuerBib     追赶者参赛号（必须已登记）
     * @param pursuedBib     被追者参赛号（必须已登记，且不同于追赶者）
     * @param pursuerStartMm 追赶者起始距离（毫米，非负整数）
     * @param pursuedStartMm 被追者起始距离（毫米，非负整数）
     */
    public record PursuitPairDefinition(
            @NotBlank String pairId,
            @NotBlank String pursuerBib,
            @NotBlank String pursuedBib,
            @NotNull @PositiveOrZero Long pursuerStartMm,
            @NotNull @PositiveOrZero Long pursuedStartMm
    ) {
    }
}
