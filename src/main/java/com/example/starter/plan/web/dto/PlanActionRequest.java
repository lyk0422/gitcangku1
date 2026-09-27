package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；operator 为操作者标识（可选，
 * 参与幂等指纹与发布快照固化，未携带时为 null）。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {
}
