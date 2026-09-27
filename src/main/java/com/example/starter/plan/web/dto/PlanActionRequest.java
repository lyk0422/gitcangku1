package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求，携带幂等键与操作者标识（操作者参与幂等指纹）。
 */
public record PlanActionRequest(@NotBlank String requestKey, @NotBlank String operator) {
}
