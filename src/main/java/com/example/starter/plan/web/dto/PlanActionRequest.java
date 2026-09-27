package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求，携带幂等键与可选操作者（空白归一为 anonymous，参与幂等指纹）。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operatorId) {
}
