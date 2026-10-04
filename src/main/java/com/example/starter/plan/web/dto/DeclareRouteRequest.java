package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 进路声明请求。requestId 为幂等键；operator 为操作者标识，参与幂等身份判定。
 */
public record DeclareRouteRequest(
        @NotBlank String requestId,
        @NotBlank String operator) {
}