package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量裁决冲线证据请求：裁判对同一计时组的一组待裁决证据给出最终名次顺序。
 * finalOrder 必须是候选集合的全排列，裁决后的名次互不重复。
 *
 * @param adjudicationId  裁决ID，全局唯一
 * @param evidenceIds     本次裁决的证据ID列表（须全部属于同一计时组且为PENDING）
 * @param finalOrder      裁决后的名次顺序（候选参赛号全排列）
 * @param operator        裁决操作者（裁判）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record AdjudicateFinishEvidenceRequest(
        @NotBlank String adjudicationId,
        @NotEmpty List<@NotBlank String> evidenceIds,
        @NotEmpty List<@NotBlank String> finalOrder,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
