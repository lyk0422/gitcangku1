package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；operator 为操作者标识，
 * 缺省按 "system" 计入幂等指纹。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {
}