package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求，携带幂等键；operator 为可选操作者标识，参与幂等指纹与重排记录固化。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {

    /**
     * 兼容仅携带幂等键的调用（operator 为 null）。
     */
    public PlanActionRequest(String requestKey) {
        this(requestKey, null);
    }
}
