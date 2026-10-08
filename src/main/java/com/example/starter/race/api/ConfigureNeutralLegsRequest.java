package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 启用中立赛段模式请求（计时前一次性固定 1~5 个中立区间，启用后不再变更）。
 *
 * @param legs            1~5 个中立区间；起点可为 0（发枪零点），终点必须是后续必经检查点；
 *                        区间不得交叠，相接合法
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 * @param operator        操作者标识，非空
 */
public record ConfigureNeutralLegsRequest(
        @NotEmpty @Size(max = 5) @Valid List<NeutralLegDefinition> legs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId,
        @NotBlank String operator
) {

    /**
     * 单个中立区间定义。
     *
     * @param startPosition 起点检查点顺序；0 表示发枪零点
     * @param endPosition   终点检查点顺序，必须严格大于起点
     */
    public record NeutralLegDefinition(
            @NotNull @PositiveOrZero Integer startPosition,
            @NotNull @Positive Integer endPosition
    ) {
    }
}
