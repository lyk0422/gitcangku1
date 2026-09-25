package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布计划请求：幂等键之外可指定操作者、期望版本与乘务指派（司机与车长须同时指定或同时缺省）。
 */
public record PublishPlanRequest(
        @NotBlank String requestKey,
        String operator,
        Integer expectedVersion,
        String driverId,
        String conductorId) {

    /**
     * 仅携带幂等键的兼容构造（不指定乘务）。
     */
    public PublishPlanRequest(String requestKey) {
        this(requestKey, null, null, null, null);
    }
}
