package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布 / 取消计划请求。requestKey 为幂等键；operator 为操作者标识（可空），
 * 参与幂等指纹与重排记录固化，null 表示调用方未提供。
 */
public record PlanActionRequest(@NotBlank String requestKey, String operator) {

    /**
     * 兼容仅携带幂等键的调用方式。
     */
    public PlanActionRequest(String requestKey) {
        this(requestKey, null);
    }
}