package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 定时航线可行性审查请求。引用现有航线版本，为每个有序航点给出 UTC 整秒到达时刻。
 * 时刻数量必须与航线航点数一致（最多 20 个航点），且严格递增。
 *
 * @param routeId              航线唯一标识
 * @param expectedRouteVersion 期望的当前航线版本，不符返回 409
 * @param airspaceVersion      明确的空域版本（原禁飞区域版本），不符返回 409
 * @param operator             提交定时计划的操作者
 * @param arrivalTimes         各航点 UTC 整秒到达时刻，ISO-8601 格式（如 2026-10-04T12:00:00Z），严格递增
 * @param requestId            写操作全局唯一请求标识，用于幂等重放
 */
public record TimedReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedRouteVersion,
        @NotNull Long airspaceVersion,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Size(min = 2, max = 20) List<@NotBlank String> arrivalTimes,
        @NotBlank @Size(max = 64) String requestId) {
}