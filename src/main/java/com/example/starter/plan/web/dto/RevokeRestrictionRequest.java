package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销气象限速令请求。requestKey 为幂等键；指纹包含操作者。
 */
public record RevokeRestrictionRequest(
        @NotBlank String requestKey,
        @NotBlank String operator) {
}
