package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；
 * preemptKey 仅发布时可选，提交即发起抢占（要求对方全部占用区段等级低于本计划）。
 */
public record PlanActionRequest(@NotBlank String requestKey, String preemptKey) {
}
