package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 进路冲突对请求项，两个进路编号无序（服务端按字典序规范化，语义对称）。
 */
public record RoutePairRequest(
        @NotBlank String routeA,
        @NotBlank String routeB) {
}