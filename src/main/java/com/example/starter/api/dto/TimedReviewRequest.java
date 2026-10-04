package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交定时航线可行性审查请求：引用现有航线版本，为每个有序航点给出 UTC 整秒到达时刻。
 * 审查通过后坐标与时刻冻结，不得重绑；变更须提交新的计划。
 *
 * @param requestId               写操作全局唯一请求标识，用于幂等重放
 * @param operator                操作者标识
 * @param routeId                 引用的航线标识
 * @param expectedRouteVersion    期望的当前航线版本，不符返回 409
 * @param expectedAirspaceVersion 期望的当前空域版本，不符返回 409
 * @param points                  有序航点（2~20 个），坐标最多 3 位小数，到达时刻严格递增
 */
public record TimedReviewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedRouteVersion,
        @NotNull Long expectedAirspaceVersion,
        @NotNull @Valid @Size(min = 2, max = 20) List<TimedPointDto> points) {
}