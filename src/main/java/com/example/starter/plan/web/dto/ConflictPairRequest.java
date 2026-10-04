package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 进路冲突对请求项，方向无关（对称）。
 */
public record ConflictPairRequest(@NotBlank String routeA, @NotBlank String routeB) {
}