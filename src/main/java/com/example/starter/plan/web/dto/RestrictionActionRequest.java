package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销限速令请求，仅携带幂等键与操作者标识（可空）。
 */
public record RestrictionActionRequest(
        @NotBlank String requestKey,
        String operator) {
}
