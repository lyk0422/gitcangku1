package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 启用追逐赛请求：计时前一次性固定 1~5 个追逐对及每人非负整数起始距离（毫米）。
 *
 * @param pairs           追逐对定义（1~5个，pairNo 从1连续递增）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 * @param operator        操作者，非空
 */
public record ConfigurePursuitRequest(
        @NotEmpty @Valid List<PursuitPairDefinition> pairs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotBlank String operator
) {

    /**
     * 追逐对定义。
     *
     * @param pairNo        追逐对序号，从1连续递增
     * @param chaserBib     追赶者参赛号
     * @param chasedBib     被追者参赛号
     * @param chaserStartMm 追赶者起始距离（毫米，非负整数）
     * @param chasedStartMm 被追者起始距离（毫米，非负整数）
     */
    public record PursuitPairDefinition(
            @NotNull Integer pairNo,
            @NotBlank String chaserBib,
            @NotBlank String chasedBib,
            @NotNull Long chaserStartMm,
            @NotNull Long chasedStartMm
    ) {
    }
}
