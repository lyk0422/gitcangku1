package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销气象限速令请求，携带幂等键与操作者标识。
 */
public record RestrictionActionRequest(
        @NotBlank String requestKey,
        @NotBlank String operator) {
}