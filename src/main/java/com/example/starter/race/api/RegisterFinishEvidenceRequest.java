package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 登记冲线证据请求：为相同计时的参赛者登记冲线证据。
 * suggestedOrder 必须是该计时组全部候选参赛号的全排列（不遗漏、不重复）。
 *
 * @param evidenceId      证据ID，全局唯一，不可重复
 * @param finishTimeMs    候选组共享的原始完赛耗时（毫秒）
 * @param suggestedOrder  建议名次顺序（候选参赛号列表）
 * @param capturedAt      证据捕获UTC时刻，Unix毫秒时间戳
 * @param operator        登记操作者
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RegisterFinishEvidenceRequest(
        @NotBlank String evidenceId,
        @NotNull @Positive Long finishTimeMs,
        @NotEmpty List<@NotBlank String> suggestedOrder,
        @NotNull @Positive Long capturedAt,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
