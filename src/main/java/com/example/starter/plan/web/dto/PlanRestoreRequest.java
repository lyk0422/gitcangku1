package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 恢复已撤销计划请求，仅携带幂等键。
 */
public record PlanRestoreRequest(@NotBlank String requestKey) {
}
