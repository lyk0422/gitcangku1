package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 限速令撤销请求。requestKey 为幂等键；撤销后限速令不再影响后续发布或改签。
 */
public record RevokeRestrictionRequest(
        @NotBlank String requestKey,
        @NotBlank String operator) {
}