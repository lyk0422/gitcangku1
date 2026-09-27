package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；operator 可选，参与幂等指纹。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {

    /**
     * 兼容仅携带幂等键的调用。
     */
    public PlanActionRequest(String requestKey) {
        this(requestKey, null);
    }
}
