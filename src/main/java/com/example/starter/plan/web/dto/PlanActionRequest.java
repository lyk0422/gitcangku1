package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；operator 为可选操作者标识，
 * 参与幂等指纹与发布快照，未携带时为 null。
 */
public record PlanActionRequest(@NotBlank String requestKey,
                                @Size(max = 128) String operator) {
}
