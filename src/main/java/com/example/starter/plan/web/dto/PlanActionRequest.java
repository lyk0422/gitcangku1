package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；
 * operator 为操作者（仅发布时用于发布快照，为空时服务端以 system 代替；取消不使用）。
 */
public record PlanActionRequest(@NotBlank String requestKey,
                                @Size(max = 64) String operator) {
}
