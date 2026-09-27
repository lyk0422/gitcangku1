package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求，携带幂等键与操作者。operator 可空，
 * 非空时进入幂等指纹；发布时固化进发布快照。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {
}
