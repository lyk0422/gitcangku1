package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销气象限速令请求。requestKey 为幂等键；operator 可选，参与幂等指纹。
 */
public record RevokeRestrictionRequest(
        @NotBlank String requestKey,
        String operator) {
}
