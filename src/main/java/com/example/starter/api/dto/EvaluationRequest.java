package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 改航候选集批量评估请求。明确指定目标航线版本与空域版本，任一不是当前版本返回 409。
 * 候选顺序属于请求参数：同 evaluationKey 换序重提视为异参，返回 409。
 *
 * @param evaluationKey   评估幂等键，全局唯一；同键同参重放首次响应快照，失败不占键
 * @param routeId         目标航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param airspaceVersion 明确的当前空域版本
 * @param candidates      2~10 个按声明顺序排列的候选点列；每个候选 2~50 个顺序点，
 *                        至少两点不同，候选之间不得完全相同
 */
public record EvaluationRequest(
        @NotBlank @Size(max = 64) String evaluationKey,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull Long airspaceVersion,
        @NotNull @Size(min = 2, max = 10) List<@Valid @Size(min = 2, max = 50) List<@Valid RoutePointDto>> candidates) {
}
