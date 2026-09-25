package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。preemptKey 仅发布时有效：携带即表示授权抢占
 * 等级更低的冲突已发布计划；不携带时任何区段冲突仍返回 422。
 */
public record PlanActionRequest(@NotBlank String requestKey, String preemptKey) {
}
