package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 限速令撤销请求，仅携带幂等键与操作者。
 */
public record RestrictionActionRequest(
        @NotBlank String requestKey,
        @NotBlank String operator) {
}
