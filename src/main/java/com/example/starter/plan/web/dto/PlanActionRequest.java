package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；operator 为可选操作者标识，
 * 参与幂等指纹（计划集合 + 限速版本 + 操作者），缺省为 null（未指定）。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {

    /**
     * 兼容构造：仅幂等键，操作者为 null。
     */
    public PlanActionRequest(String requestKey) {
        this(requestKey, null);
    }
}
