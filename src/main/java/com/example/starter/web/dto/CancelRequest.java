package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 研究组撤销请求体；requestId 用于撤销接口幂等。
 */
public record CancelRequest(
        @NotBlank String researchGroup,
        String requestId
) {
}
