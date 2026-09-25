package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求，携带幂等键；operator 可选，提供时计入幂等指纹。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {

    /**
     * 仅携带幂等键的便捷构造（操作者缺省）。
     */
    public PlanActionRequest(String requestKey) {
        this(requestKey, null);
    }
}
