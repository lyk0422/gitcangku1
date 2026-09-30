package com.example.starter.film.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审核通过请求。
 *
 * @param requestId 幂等请求ID，非空，最长 64
 */
public record ApproveRequest(
        @NotBlank @Size(max = 64)
        String requestId
) {
}
