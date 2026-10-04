package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册进路请求。requestId 为幂等键；operator 为操作者标识，计入幂等身份。
 */
public record CreateRouteRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank @Size(max = 64) String routeId) {
}