package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 改航候选集批量评估请求。
 *
 * <p>候选按声明顺序排列（2～10 个），每个候选为 2～50 个顺序航点；
 * 候选顺序属于请求参数，换序视为异参。evaluationKey 全局唯一，
 * 同键同参重放首次响应快照，失败不占键。</p>
 *
 * @param evaluationKey   评估全局唯一标识（同时作为幂等键）
 * @param routeId         目标航线唯一标识
 * @param expectedVersion 期望的当前航线版本，不符返回 409
 * @param airspaceVersion 明确的当前空域版本，不符返回 409
 * @param candidates      按声明顺序排列的改航候选点列（2～10 个）
 */
public record RerouteEvaluationRequest(
        @NotBlank @Size(max = 64) String evaluationKey,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull Long airspaceVersion,
        @NotNull @Size(min = 2, max = 10) @Valid
        List<@NotNull @Size(min = 2, max = 50) @Valid List<@NotNull @Valid RoutePointDto>> candidates) {
}
