package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求，仅携带幂等键。
 */
public record PlanActionRequest(@NotBlank String requestKey) {
}
