package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；operator 为操作者标识（可空），
 * 参与发布操作的幂等指纹（计划集合、限速版本与操作者）。
 */
public record PlanActionRequest(
        @NotBlank String requestKey,
        String operator) {

    /**
     * 兼容构造：仅幂等键，操作者为空。
     */
    public PlanActionRequest(String requestKey) {
        this(requestKey, null);
    }
}
